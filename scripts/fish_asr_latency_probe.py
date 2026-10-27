"""Bounded Fish-only measurement: realtime ASR (transcribe-stream-1) turn timing.

Synthesized input speech (Fish TTS) is streamed into one realtime ASR session at
speaker pace. Measures, per utterance: end-of-audio -> eager_end_of_turn and
end-of-audio -> committed transcript. This is provider-ASR-only timing with
synthesized input: NOT end-to-end, acoustic, or device speed, and no LLM/TTS
reply stage. Small n. Draws existing Fish API credit only (ASR session minutes +
a few hundred bytes of TTS), per the user's 2026-10-28 01:11 approval.
"""
import asyncio, json, os, re, sys, time

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
# A/B additions: (text, parts) where parts are (pcm_index_or_text, pause_ms_after)
# pause-injected negations/pauses are assembled from separately synthesized fragments
AB_FRAGMENTS = [
    "I am not",                          # 4
    "saying the room is dirty.",         # 5
    "Could I",                           # 6
    "get a late checkout on Sunday?",    # 7
    "We are arriving late on Friday night, probably around midnight, and we would like to keep the reservation without any problem.",  # 8
    "The room is not bad",               # 9
    "but the air conditioning is not working at all.",  # 10
]
AB_CASES = [
    ("negation-pause-600ms", [(4, 600), (5, 0)], "I am not saying the room is dirty."),
    ("pause-700ms", [(6, 700), (7, 0)], "Could I get a late checkout on Sunday?"),
    ("long-utterance", [(8, 0)], AB_FRAGMENTS[4]),
    ("double-negation-400ms", [(9, 400), (10, 0)], "The room is not bad but the air conditioning is not working at all."),
]

CACHE = "/tmp/asr-probe-pcm"

def assemble(parts, pcm_by_index):
    out = b""
    for idx, pause_ms in parts:
        out += pcm_by_index[idx]
        if pause_ms:
            out += bytes(32 * pause_ms)  # 32 bytes per ms at 16 kHz s16le mono
    return out

def clean(text):
    return re.sub(r"<\|speaker:\d+\|>", "", text).strip()

async def main():
    key = os.environ["FISH_API_KEY"]
    speech = []
    mode = "auto"
    vad_ms = None
    if "--vad" in sys.argv:
        vad_ms = int(sys.argv[sys.argv.index("--vad") + 1])
        mode = f"vad{vad_ms}"
    if "--asr-only" in sys.argv or "--ab" in sys.argv:
        all_texts = UTTERANCES + AB_FRAGMENTS
        pcm_by_index = {}
        for i, text in enumerate(all_texts):
            path = f"{CACHE}/{i}.pcm"
            pcm_by_index[i] = open(path, "rb").read()
        speech = [(text, pcm_by_index[i]) for i, text in enumerate(UTTERANCES)]
        for name, parts, full in AB_CASES:
            speech.append((name, assemble(parts, pcm_by_index)))
        print(f"loaded {len(speech)} utterances (4 plain + 4 A/B), mode={mode}", flush=True)
    else:
        os.makedirs(CACHE, exist_ok=True)
        tts = FishAudio()
        for i, text in enumerate(UTTERANCES + AB_FRAGMENTS):
            pcm = tts.tts.convert(text=text, format="pcm", model="s2-pro",
                                  config=TTSConfig(format="pcm", sample_rate=16000, normalize=True))
            open(f"{CACHE}/{i}.pcm", "wb").write(pcm)
            print(f"synthesized {len(pcm)} bytes [{i}]: {text[:40]}", flush=True)
        return  # synthesis-only pass; ASR sessions run with --ab / --asr-only

    results = []
    ab_expected = {name: full for name, _, full in AB_CASES}
    asr = FishASR(key)
    if vad_ms is not None:
        # session must START in vad mode: mid-session switches keep auto's judgement
        # semantics per Fish docs; rewrite the initial config instead of a second config.
        orig_send = asr._send
        sent_first = False
        async def send_with_vad(message):
            nonlocal sent_first
            if not sent_first and message.get('type') == 'config':
                message['turn_detection'] = {'mode': 'vad', 'silence_timeout_ms': vad_ms}
                sent_first = True
            await orig_send(message)
        asr._send = send_with_vad
    async with asr:
        session_t0 = time.time()
        for text, pcm in speech:
            events = []
            async def collect():
                while True:
                    ev = await asr.events.get()
                    events.append((time.time(), ev))
            collector = asyncio.create_task(collect())
            chunk = 3200  # 100 ms at 16 kHz s16le mono
            for off in range(0, len(pcm), chunk):
                await asr.send_audio(pcm[off:off+chunk])
                await asyncio.sleep(0.08)  # paced just under real-time; provider buffers
            end_of_audio = time.time()
            # A real microphone keeps sending frames after speech ends; the provider's
            # turn detection needs those silence frames to finalize the turn.
            silence_end = end_of_audio + 2.5
            while time.time() < silence_end:
                await asr.send_audio(bytes(chunk))
                await asyncio.sleep(0.08)
            # drain: wait for the last commit covering this utterance (cap 8s)
            deadline = time.time() + 8
            while time.time() < deadline:
                commits = [e for t, e in events if e.get("type") == "committed"]
                if commits and all(t < time.time() - 1.2 for t, e in events):
                    break
                await asyncio.sleep(0.2)
            collector.cancel()
            commits = [(t, e) for t, e in events if e.get("type") == "committed"]
            eager = next((t for t, e in events if e.get("type") == "eager_end_of_turn"), None)
            joined = clean(" ".join(e.get("text", "") for _, e in commits))
            last_commit_at = commits[-1][0] if commits else None
            r = {
                "input": text,
                "end_to_eager_ms": round((eager - end_of_audio) * 1000) if eager else None,
                "end_to_committed_ms": round((last_commit_at - end_of_audio) * 1000) if last_commit_at else None,
                "transcript": joined if text in ab_expected else joined[:120],
                "language": commits[-1][1].get("language_code") if commits else None,
                "user_turn": commits[-1][1].get("user_turn") if commits else None,
                "turns_committed": len(commits),
                "commit_reasons": [e.get("reason") for _, e in commits],
            }
            if text in ab_expected:
                norm = lambda s: re.sub(r" +", " ", re.sub(r"[^a-zñáéíóú¿? ]", "", s.lower())).strip()
                got, want = norm(r["transcript"]), norm(ab_expected[text])
                r["expected"] = ab_expected[text]
                r["transcript_matches_input"] = got == want
            results.append(r)
            print(json.dumps(r), flush=True)
            events.clear()
        await asr.finish()
    summary = {"mode": mode, "n": len(results), "session_wall_s": round(time.time() - session_t0, 1),
               "median_end_to_committed_ms": sorted(r["end_to_committed_ms"] for r in results)[len(results)//2],
               "median_end_to_eager_ms": (lambda e: sorted(e)[len(e)//2] if e else None)([r["end_to_eager_ms"] for r in results if r["end_to_eager_ms"] is not None])}
    print("SUMMARY " + json.dumps(summary))

asyncio.run(main())
