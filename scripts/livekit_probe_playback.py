"""Headless Chromium render boundary for the two LiveKit probes.

Uses the shipped AudioWorklet, not a sender timer or arrival acknowledgement.
Chromium rendering is still not physical speaker/acoustic acceptance. Requires
requirements-browser.txt and its installed Chromium, before any paid probe call.
Set PLAYWRIGHT_CHROMIUM_EXECUTABLE to an explicit existing browser if desired;
PLAYWRIGHT_BROWSERS_PATH otherwise selects the already-installed browser cache.
Output is always muted: no synthetic probe sounds reach the host speakers.
"""
import asyncio
import json
import os
from pathlib import Path


from voice_worker import contract


class ProbePlayback:
    def __init__(self, room, agent_identity):
        self.room, self.agent_identity = room, agent_identity
        self.queue = asyncio.Queue(maxsize=16)
        self.error = None
        self.received = asyncio.Event()
        self.pcm = bytearray()
        self._playwright = self._browser = self._task = None
        self._active_generation = 0
        self._generation = 0
        self._sequence = 0

    async def start(self):
        from playwright.async_api import async_playwright
        self._playwright = await async_playwright().start()
        try:
            self._browser = await self._playwright.chromium.launch(headless=True,
                executable_path=os.environ.get('PLAYWRIGHT_CHROMIUM_EXECUTABLE') or None,
                args=['--mute-audio', '--autoplay-policy=no-user-gesture-required'])
            page = await self._browser.new_page()
            worklet = (Path(__file__).resolve().parents[1] / 'ui/web/playback-worklet.js').read_text()

            async def route(request):
                if request.request.url.endswith('/playback-worklet.js'):
                    await request.fulfill(status=200, content_type='application/javascript', body=worklet)
                else:
                    await request.fulfill(status=200, content_type='text/html', body='<!doctype html><title>PCM render probe</title>')

            await page.route('http://127.0.0.1/**', route)
            await page.expose_function('rendered', self._rendered)
            await page.goto('http://127.0.0.1/probe')
            await page.evaluate('''async () => {
                const context = new AudioContext({sampleRate: 24000});
                if (context.sampleRate !== 24000) throw new Error('Unsupported render rate');
                await context.audioWorklet.addModule('/playback-worklet.js');
                const node = new AudioWorkletNode(context, 'translation-playback', {
                    numberOfInputs: 0, numberOfOutputs: 1, outputChannelCount: [1]
                });
                node.port.onmessage = event => { void window.rendered(event.data); };
                node.connect(context.destination);
                await context.resume();
                if (context.state !== 'running') throw new Error('Renderer not running');
                window.renderNode = node;
                window.renderContext = context;
            }''')
            self._page = page
            self._task = asyncio.create_task(self._consume())
        except BaseException:
            await self.close()
            raise

    def accept(self, packet):
        if (not packet.participant or packet.participant.identity != self.agent_identity
                or packet.topic not in (contract.EVENT_TOPIC, contract.AUDIO_TOPIC)):
            return
        try:
            if packet.topic == contract.EVENT_TOPIC and json.loads(packet.data).get('type') != 'translation.output':
                return
            self.queue.put_nowait((packet.topic, bytes(packet.data)))
        except (ValueError, AttributeError):
            self.error = RuntimeError('Probe output event malformed')
        except asyncio.QueueFull:
            self.error = RuntimeError('Probe render queue overflow')

    async def _consume(self):
        try:
            while True:
                topic, data = await self.queue.get()
                if topic == contract.EVENT_TOPIC:
                    event = json.loads(data)
                    if event.get('type') != 'translation.output':
                        continue
                    generation = event['output_generation']
                    if event['status'] == 'playing' and generation > self._generation:
                        self._active_generation = generation
                        self._generation, self._sequence = generation, 0
                        await self._page.evaluate('(generation) => renderNode.port.postMessage({type:"begin",generation})', generation)
                    elif generation == self._generation and event['status'] in ('interrupted', 'error'):
                        self._active_generation = 0
                        await self._page.evaluate('(generation) => renderNode.port.postMessage({type:"flush",generation})', generation)
                else:
                    generation, sequence, pcm = contract.read_audio_packet(data)
                    if generation != self._active_generation:
                        continue
                    if sequence != self._sequence:
                        raise RuntimeError('Probe PCM sequence mismatch')
                    self._sequence += 1
                    await self._page.evaluate('''({generation, data}) => {
                        const bytes = new Uint8Array(data);
                        renderNode.port.postMessage({type:'pcm', generation, bytes}, [bytes.buffer]);
                    }''', {'generation': generation, 'data': list(data)})
                    self.pcm.extend(pcm)
                    if pcm:
                        self.received.set()
        except asyncio.CancelledError:
            raise
        except Exception:
            self.error = RuntimeError('Probe renderer failed')

    async def _rendered(self, event):
        if event.get('type') == 'error':
            self.error = RuntimeError('Probe worklet rejected PCM')
        elif event.get('type') == 'played' and event.get('generation') == self._active_generation:
            try:
                await self.room.local_participant.publish_data(json.dumps({
                    'type': 'translation.played', 'output_generation': event['generation'],
                    'sequence': event['sequence']}), reliable=True, topic='client-event')
            except Exception:
                self.error = RuntimeError('Probe render acknowledgement failed')

    async def close(self):
        if self._task:
            self._task.cancel()
            await asyncio.gather(self._task, return_exceptions=True)
            self._task = None
        if self._browser:
            await self._browser.close()
            self._browser = None
        if self._playwright:
            await self._playwright.stop()
            self._playwright = None
