#!/usr/bin/env python3
"""Hosted-route probe: does Fish's hosted agent (production config replica) speak a
drafted reply from a mid-sentence-paused partial before the speaker resumes?

Replicates FishPlan.agentConfig + sessionBody exactly (en guest, es staff), creates a
throwaway probe agent, opens a hosted session (LiveKit transport), publishes the cached
negation-pause-600ms utterance (PCM fragments 4+5 from the ASR probe), records all agent
audio with arrival times, and reports whether agent speech started before the second
fragment finished - i.e. whether a wrong drafted reply became AUDIBLE.

Draws existing Fish API credit only (one agent session, ~1 min). NOT a device measurement.
"""
import asyncio, json, os, sys, time
import numpy as np
import urllib.request
from livekit import rtc

API = "https://api.fish.audio"
KEY = os.environ["FISH_API_KEY"]
PROMPT = open("/tmp/harness/interpreter-prompt.txt").read()
PCM_DIR = "/tmp/asr-probe-pcm"

AGENT_CONFIG = {
    "prompt": {"system_prompt": PROMPT, "first_message_mode": "fixed",
               "first_message": "Hello! How can I help you today?"},
    "voice": {"voice_id": "802e3bc2b27e49c2995d23ef70e6ac89", "speaking_language": "es",
              "tts_model": "s2.1-pro", "expressive": False},
    "llm": {"model": "google/gemini-3.8-flash"},
    "asr": {"model": "elevenlabs:scribe_v2_realtime", "multilingual": True, "strict_language": False},
    "tools": {"enabled": False, "tool_ids": []},
    "knowledge_base": {"enabled": False, "knowledge_source_ids": []},
    "conversation": {"max_duration_seconds": 600, "record_audio": False, "timezone": "Asia/Calcutta",
                     "interruptible": True, "interruption_sensitivity": "high",
                     "echo_guard": {"web_voice": False},
                     "response_wait_ms": 300, "reengage_enabled": False,
                     "response_max_wait_ms": 600, "speculative_response": True},
}

# candidate-config overrides for fix-specific A/B (throwaway agents only)
_w = os.environ.get("PROBE_WAIT_MS")
if _w:
    AGENT_CONFIG["conversation"]["response_wait_ms"] = int(_w)
_mw = os.environ.get("PROBE_MAX_WAIT_MS")
if _mw:
    AGENT_CONFIG["conversation"]["response_max_wait_ms"] = None if _mw == "null" else int(_mw)
_asr = os.environ.get("PROBE_ASR_MODEL")
if _asr:
    AGENT_CONFIG["asr"]["model"] = _asr
_extra = os.environ.get("PROBE_APPEND_PROMPT")
if _extra:
    AGENT_CONFIG["prompt"]["system_prompt"] = PROMPT + "\n" + _extra

LAST_DATE = [None]
def api(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, method=method,
        headers={"Authorization": "Bearer " + KEY, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            LAST_DATE[0] = (r.headers.get("Date"), time.monotonic())
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{method} {path} -> {e.code}: {e.read()[:200]}")
    return json.loads(raw) if raw else {}

def load16k(i):
    pcm = open(f"{PCM_DIR}/{i}.pcm", "rb").read()
    a = np.frombuffer(pcm, dtype=np.int16).astype(np.float32)
    t = np.arange(len(a)) / 16000.0
    t48 = np.arange(len(a) * 3) / 48000.0
    return np.interp(t48, t, a).astype(np.int16)

async def main():
    t0 = time.monotonic()
    log = lambda m: print(f"[{time.monotonic()-t0:6.2f}] {m}", flush=True)

    agent = api("POST", "/v1/agent/agents", {"name": f"CTO1 hosted probe {int(time.time())}",
                "description": "throwaway latency probe", "config": AGENT_CONFIG})
    agent_id = agent["agent_id"]
    log(f"probe agent {agent_id}")
    api("POST", f"/v1/agent/agents/{agent_id}/publish", {})
    log("agent published")
    session = api("POST", "/v1/agent/sessions", {
        "agent_id": agent_id, "timezone": "Asia/Calcutta", "record_audio": False,
        "tool_events": False, "world_context": False, "end_user_id": "ctoprobe-001",
        "metadata": {"manzanilla_config_rev": "15", "guest_language": "en", "staff_language": "es",
                     "asr_model": "elevenlabs:scribe_v2_realtime",
                     "llm_model": "google/gemini-3.8-flash", "tts_model": "s2.1-pro"},
        "overrides": {"system_prompt": AGENT_CONFIG["prompt"]["system_prompt"], "language": "es",
                      "asr": {"model": AGENT_CONFIG["asr"]["model"]}}})
    sid = session["session_id"]
    from datetime import datetime, timezone
    import email.utils
    srv0 = None
    t_session_local = None
    if LAST_DATE[0] and LAST_DATE[0][0]:
        srv0 = email.utils.parsedate_to_datetime(LAST_DATE[0][0]).timestamp()
        t_session_local = LAST_DATE[0][1]
    def srv_to_local(iso):
        if not srv0 or not iso: return None
        return t_session_local + (datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp() - srv0)
    log(f"session {sid} transport={session.get('transport')}")

    room = rtc.Room()
    agent_frames = []  # (arrival_monotonic, np.int16 array)
    @room.on("track_subscribed")
    def on_sub(track: rtc.Track, pub, participant):
        log(f"subscribed {track.kind} from {participant.identity}")
        if track.kind == rtc.TrackKind.KIND_AUDIO:
            stream = rtc.AudioStream(track, sample_rate=48000, num_channels=1)
            async def drain():
                async for ev in stream:
                    agent_frames.append((time.monotonic(), np.frombuffer(ev.frame.data, dtype=np.int16).copy()))
            asyncio.create_task(drain())

    await room.connect(session["livekit_url"], session["token"])
    log("room connected")
    source = rtc.AudioSource(48000, 1)
    track = rtc.LocalAudioTrack.create_audio_track("microphone", source)
    await room.local_participant.publish_track(track, rtc.TrackPublishOptions(source=rtc.TrackSource.SOURCE_MICROPHONE))
    log("mic track published")

    async def push(samples48):
        chunk = 4800  # 100ms
        for off in range(0, len(samples48), chunk):
            frame = rtc.AudioFrame(data=samples48[off:off+chunk].tobytes(),
                                   sample_rate=48000, num_channels=1, samples_per_channel=len(samples48[off:off+chunk]))
            await source.capture_frame(frame)
            await asyncio.sleep(0.098)

    # wait for greeting: agent speech then 2s of quiet
    log("waiting for greeting...")
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        speech = [t for t, a in agent_frames if float(np.sqrt(np.mean(a.astype(np.float64)**2))) > 300]
        if speech and time.monotonic() - speech[-1] > 2.0:
            break
        await asyncio.sleep(0.2)
    if agent_frames:
        ga = np.concatenate([a for _, a in agent_frames])
        log(f"greeting done ({len(agent_frames)} frames, rms={float(np.sqrt(np.mean(ga.astype(np.float64)**2))):.1f})")
    open("/tmp/hosted-probe-agent-all.pcm", "wb").write(b"".join(a.tobytes() for _, a in agent_frames))

    # case spec: comma list of fragment indexes, with numeric pause markers in ms
    spec = sys.argv[1] if len(sys.argv) > 1 else "4,600,5"
    parts = []
    frag4_end = frag5_start = 0.0
    cursor = 0.0
    for tok in spec.split(","):
        if tok.isdigit() and int(tok) < 100:  # fragment index
            a = load16k(int(tok))
            parts.append(a)
            cursor += len(a) / 48000.0
        elif tok.startswith("n") and tok[1:].isdigit():  # room-tone pause in ms
            n = int(48000 * int(tok[1:]) / 1000)
            noise = (np.random.default_rng(7).standard_normal(n) * 90).astype(np.int16)
            parts.append(noise)
            if cursor > 0 and frag4_end == 0.0:
                frag4_end = cursor
            cursor += int(tok[1:]) / 1000.0
            frag5_start = cursor
        else:  # pause in ms (digital silence)
            parts.append(np.zeros(int(48000 * int(tok) / 1000), dtype=np.int16))
            if cursor > 0 and frag4_end == 0.0:
                frag4_end = cursor
            cursor += int(tok) / 1000.0
            frag5_start = cursor
    utter = np.concatenate(parts)
    t_start = time.monotonic()
    await push(utter)
    t_end = time.monotonic()
    frag4_end = t_start + frag4_end
    frag5_start = t_start + frag5_start
    log(f"utterance pushed: frag4_end=+{frag4_end-t_start:.2f}s frag5_start=+{frag5_start-t_start:.2f}s end=+{t_end-t_start:.2f}s")
    # keep mic alive with silence while waiting for the reply; energy-track agent speech
    ispec = sys.argv[2] if len(sys.argv) > 2 else ""
    i_delay = float(ispec[1:ispec.index(":")]) if ispec else None
    i_frags = ispec[ispec.index(":")+1:] if ispec else ""
    wait_until = time.monotonic() + 25
    sil = np.zeros(4800, dtype=np.int16)
    speech_tl = []  # (t, loud) for frames after t_start
    first_audio_t = None
    interrupt_done = False
    i_t0 = i_t1 = None
    while time.monotonic() < wait_until:
        now = time.monotonic()
        for t, a_ in agent_frames[len(speech_tl):]:
            if t >= t_start - 0.1:
                loud = float(np.sqrt(np.mean(a_.astype(np.float64)**2))) > 300
                speech_tl.append((t, loud))
        for t, loud in speech_tl:
            if loud and first_audio_t is None and t >= t_end - 0.2:
                first_audio_t = t
                log(f"first agent audio at +{t - t_start:.2f}s (eos->audio {t - t_end:.2f}s)")
                break
        if i_delay is not None and first_audio_t and not interrupt_done and now >= first_audio_t + i_delay:
            parts_i = [load16k(int(x)) for x in i_frags.split(",") if x.isdigit()]
            i_t0 = time.monotonic()
            for a_ in parts_i:
                await push(a_)
            i_t1 = time.monotonic()
            interrupt_done = True
            log(f"interrupt pushed +{i_t0 - t_start:.2f}s..+{i_t1 - t_start:.2f}s")
        await source.capture_frame(rtc.AudioFrame(data=sil.tobytes(), sample_rate=48000, num_channels=1, samples_per_channel=4800))
        await asyncio.sleep(0.098)

    # segment agent audio that arrived after t_start (post-greeting)
    post = [(t, a) for t, a in agent_frames if t >= t_start - 0.5]
    segs = []
    for t, a in post:
        if segs and t - segs[-1][1] < 0.35:
            segs[-1][1] = t
            segs[-1][2].append(a)
        else:
            segs.append([t, t, [a]])
    out = []
    for i, (s, e, chunks) in enumerate(segs):
        pcm = np.concatenate(chunks)
        rms = float(np.sqrt(np.mean(pcm.astype(np.float64) ** 2))) if len(pcm) else 0.0
        path = f"/tmp/hosted-probe-agent-seg{i}.pcm"
        open(path, "wb").write(pcm.tobytes())
        out.append({"seg": i, "start_rel_utterance": round(s - t_start, 2),
                    "end_rel_utterance": round(e - t_start, 2), "rms": round(rms),
                    "seconds": round(len(pcm) / 48000, 2), "file": path,
                    "during_pause_or_frag5": s - t_start < (len(utter) / 48000.0)})
    # energy-based speech segments
    esegs = []
    for t, loud in speech_tl:
        if loud:
            if esegs and t - esegs[-1][1] < 0.5:
                esegs[-1][1] = t
            else:
                esegs.append([t, t])
    latency = {"eos_to_first_audio_s": round(first_audio_t - t_end, 2) if first_audio_t else None,
               "energy_speech_segments_rel_eos": [[round(a_ - t_end, 2), round(b_ - t_end, 2)] for a_, b_ in esegs],
               "interrupt_pushed_rel_eos": [round(i_t0 - t_end, 2), round(i_t1 - t_end, 2)] if i_t0 else None}
    print("LATENCY " + json.dumps(latency), flush=True)
    result = {"session_id": sid, "agent_id": agent_id, "frag4_end": round(frag4_end - t_start, 2),
              "frag5_start": round(frag5_start - t_start, 2), "utterance_len": round(len(utter)/48000, 2),
              "agent_segments": out}
    print("RESULT " + json.dumps(result, indent=1), flush=True)

    try:
        await room.disconnect()
    except Exception:
        pass
    try:
        time.sleep(2)
        hist = api("GET", f"/v1/agent/sessions/{sid}")
        its = hist.get("items", [])
        for it in its:
            it["created_local_rel_eos"] = round(srv_to_local(it["created_at"]) - t_end, 2) if srv_to_local(it.get("created_at")) else None
        print("HISTORY " + json.dumps(its, ensure_ascii=False), flush=True)
    except Exception as e:
        log(f"history fetch failed: {e}")
    try:
        api("POST", f"/v1/agent/sessions/{sid}/end", {})
        log("session ended")
    except Exception as e:
        log(f"session end failed: {e}")
    # clean up all throwaway probe agents, including any from failed earlier runs
    try:
        agents = api("GET", "/v1/agent/agents?search=CTO1%20hosted%20probe&page_size=20").get("agents", [])
        for a in agents:
            if a.get("name", "").startswith("CTO1 hosted probe"):
                api("DELETE", f"/v1/agent/agents/{a['agent_id']}")
                log(f"deleted probe agent {a['agent_id']}")
    except Exception as e:
        log(f"probe agent cleanup failed: {e}")

asyncio.run(main())
