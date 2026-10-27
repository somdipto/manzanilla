"""Bounded Fish-only measurement: realtime ASR (transcribe-stream-1) turn timing.

Synthesized input speech (Fish TTS) is streamed into one realtime ASR session at
speaker pace. Measures, per utterance: end-of-audio -> eager_end_of_turn and
end-of-audio -> committed transcript. This is provider-ASR-only timing with
synthesized input: NOT end-to-end, acoustic, or device speed, and no LLM/TTS
reply stage. Small n. Draws existing Fish API credit only (ASR session minutes +
a few hundred bytes of TTS), per the user's 2026-10-28 01:11 approval.
"""
import asyncio, json, os, sys, time

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'voice-worker'))
from voice_worker.asr import FishASR  # the self-hosted route's actual client

from fishaudio import FishAudio
from fishaudio.types import TTSConfig

UTTERANCES = [
    "Hello, I have a reservation for tonight.",
    "¿A qué hora es el desayuno por la mañana?",
    "What time do I need to check out tomorrow?",
    "¿Puede llamarme un taxi para las ocho?",
]

CACHE = "/tmp/asr-probe-pcm"

async def main():
    key = os.environ["FISH_API_KEY"]
    speech = []
    if "--asr-only" in sys.argv:
        for i, text in enumerate(UTTERANCES):
            speech.append((text, open(f"{CACHE}/{i}.pcm", "rb").read()))
        print(f"loaded {len(speech)} cached utterances", flush=True)
    else:
        os.makedirs(CACHE, exist_ok=True)
        tts = FishAudio()
        for i, text in enumerate(UTTERANCES):
            pcm = tts.tts.convert(text=text, format="pcm", model="s2-pro",
                                  config=TTSConfig(format="pcm", sample_rate=16000, normalize=True))
            open(f"{CACHE}/{i}.pcm", "wb").write(pcm)
            speech.append((text, pcm))
            print(f"synthesized {len(pcm)} bytes for: {text[:40]}", flush=True)
        return  # synthesis-only pass; ASR session runs with --asr-only

    results = []
    async with FishASR(key) as asr:
        session_t0 = time.time()
        for text, pcm in speech:
            chunk = 3200  # 100 ms at 16 kHz s16le mono
            for off in range(0, len(pcm), chunk):
                await asr.send_audio(pcm[off:off+chunk])
                await asyncio.sleep(0.08)  # paced just under real-time; provider buffers
            end_of_audio = time.time()
            # A real microphone keeps sending frames after speech ends; the provider's
            # turn detection needs those silence frames to finalize the turn.
            silence_end = end_of_audio + 1.5
            while time.time() < silence_end:
                await asr.send_audio(bytes(chunk))
                await asyncio.sleep(0.08)
            eager_at = committed_at = None
            committed = None
            while committed_at is None:
                ev = await asyncio.wait_for(asr.events.get(), timeout=20)
                now = time.time()
                if ev.get("type") == "eager_end_of_turn" and eager_at is None:
                    eager_at = now
                elif ev.get("type") == "committed":
                    committed_at, committed = now, ev
            results.append({
                "input": text,
                "end_to_eager_ms": round((eager_at - end_of_audio) * 1000) if eager_at else None,
                "end_to_committed_ms": round((committed_at - end_of_audio) * 1000),
                "transcript": committed.get("text", "")[:80],
                "language": committed.get("language_code"),
                "user_turn": committed.get("user_turn"),
                "turn_kind": committed.get("turn_kind"),
            })
            print(json.dumps(results[-1]), flush=True)
        await asr.finish()
    summary = {"n": len(results), "session_wall_s": round(time.time() - session_t0, 1),
               "median_end_to_committed_ms": sorted(r["end_to_committed_ms"] for r in results)[len(results)//2],
               "median_end_to_eager_ms": sorted(r["end_to_eager_ms"] for r in results if r["end_to_eager_ms"])[max(0, len([r for r in results if r['end_to_eager_ms']])//2)]}
    print("SUMMARY " + json.dumps(summary))

asyncio.run(main())
