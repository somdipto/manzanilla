# Voice latency investigation and fix: 17.0.25 candidate

Investigated on 2026-10-21 from latest `main`, commit
`8b7c006b53598f275b73c171a6d44b5f646f923d` (17.0.24/code 54).
The candidate is 17.0.25/code 55, Fish configuration revision 12.

The implementation removes documented sources of delay and opening speech loss.
It does **not** establish that a tablet now meets the acoustic latency target.
No Fish credential, real session trace or attached reception tablet is available
in the verification environment. No provider call was started and no production agent was edited.

## Findings

The actual translation path is microphone → WebRTC/LiveKit → Fish's hosted
recognizer/turn detector → Gemini 3.8 Flash → Fish S2.1 Pro → WebRTC → Android
playback. The optional `cloud/fish.py` service provisions a transport ticket;
it does not relay or buffer every audio packet. Captions use a separate channel.
`LiveSpeaker`'s PCM queue is not used for this Fish/LiveKit playback path.

| Finding in 17.0.24 | Evidence and consequence | Candidate change |
| --- | --- | --- |
| Every language pair uses Scribe v2 Realtime | Fish documents approximately 0.9 s from the last word to transcript, versus approximately 0.4 s for its Nova-3 route. The earlier decision's 150 ms partial result is the wrong timing boundary. | Select Nova-3 per session only when **both** languages are in Fish's hosted switching set; retain Scribe for all other pairs. |
| `echo_guard` is omitted | Fish's documented default for app/web calls suppresses interruption and drops caller speech during the first three seconds after the agent first speaks. This is an opening window, **not** a three-second delay on every turn. | Explicit `conversation.echo_guard: {"web_voice": false}`. Hardware/WebRTC AEC and noise suppression stay enabled. Test opening overlap and self-echo on the device. |
| `voice.expressive=true` | Fish adds delivery cues, pauses, emphasis and fillers. This conflicts with a literal interpreter's requested plain delivery. | Disable expressive mode on both provisioning paths. No numeric TTFA saving is assumed. |
| Java audio playback never requests low-latency mode | The pinned LiveKit 2.29.0 `RTCModule.audioModule` invokes the app's builder customizer; WebRTC's `JavaAudioDeviceModule.Builder` defaults `useLowLatency` to false. | Request `setUseLowLatency(true)` on that builder. Android may decline the request; no manual buffer dropping or forced sample-rate change. |
| Cached agents retain old defaults | Standalone metadata and managed provisioning reuse published identities. | Revision 12 changes the full-config identity, producing a new agent when the candidate next starts a conversation. Existing live sessions retain their version. |
| Local logs cannot identify the corresponding Fish history | The client logs its local session/generation but previously omitted the returned provider session id. | Log that validated id and requested recognizer, plus thinking/recovered-error receipts. Add safe, read-only history diagnostics. |

The documented ASR difference is approximately **500 ms on eligible pairs**.
This is a provider-stage estimate, not a measured reduction in this app, and
cannot be added to endpointing/TTS medians to predict total latency: speculation
and other stages overlap. It does not explain the whole reported 2–3 seconds.
The residual hosted LLM/synthesis/scheduling, network and device contributions
remain unmeasured.

## Recognizer routing and language coverage

Fish's hosted Nova-3 multilingual switching set is exactly:

`en es fr de hi ru pt ja it nl`

Both selected languages must belong to that set. For example:

| Guest ↔ receptionist | Recognizer |
| --- | --- |
| Spanish ↔ German | Nova-3 |
| French ↔ English | Nova-3 |
| Japanese ↔ Spanish | Nova-3 |
| Chinese ↔ Spanish | Scribe v2 Realtime |
| Korean ↔ English | Scribe v2 Realtime |
| Arabic ↔ French | Scribe v2 Realtime |

This deliberately uses Fish's integration coverage, not Deepgram's broader
standalone monolingual support list. `asr.multilingual=true` and
`asr.strict_language=false` remain explicit. The recognizer is supplied through
the documented **nested** `overrides.asr.model` session field, so changing a pair
does not mutate a shared published agent or select a second paid session.
Chinese and every other picker language remain selectable.

This change trades recognizer choice for the documented finalization speed on
eligible pairs. It does **not** prove equal bilingual transcription accuracy.
Before production acceptance, check accented speech, names, negation, numbers,
dates and currency in each deployed pair. If a pair regresses, route it back to
Scribe and increment the configuration revision for consistent rollout.

## What stays in the pipeline

Gemini 3.8 Flash and Fish S2.1 Pro, the selected voice, faithful complete
translations, the 52-language picker, duration/ownership safeguards and existing
screen behavior remain in place. Response wait stays at Fish's documented floor
of 300 ms; max wait remains 600 ms; speculation remains enabled from 17.0.23.
There is no unsupported sub-300-ms setting in this patch.

LiveKit remains Fish's documented transport. Replacing it would require owning
another streaming ASR/translation/TTS/echo/interrupt/recovery stack. No measured
evidence here implicates the library itself. The supported Android playback
change addresses one actual client setting without that rewrite.

The hosted LLM config schema exposes `model` or `custom`, not a thinking-budget
parameter. Adding a guessed `thinking_budget` field would fail validation.
Likewise, standalone Fish TTS `latency`/chunk settings cannot be assumed to apply
to the managed agent's voice schema. Moving to Flash Lite or a custom streaming
LLM is a separate quality/latency experiment, not a verified fix in this patch.
Preview recognition models are not enabled without published coverage/entitlement.

## Reproduction and measurements

Use an isolated test device/property, synthetic utterances and the existing
[acoustic protocol](voice-latency-plan.md) and [acceptance targets](TEST-PLAN.md).
That older plan's no-implementation scope described its original planning task;
the current request authorizes this implementation. Its evidence boundaries
and quality checks still apply.

1. Install the candidate through the existing signed release process.
   A debug build has no embedded provider key and does not replace the installed
   production app's signature. Updating source alone does not update a tablet.
2. End the previous conversation and start a new one. Confirm revision 12 and
   the requested pair/model. Both app and backend must be updated for managed
   devices; updating only an APK cannot alter a previously deployed backend.
3. Capture monotonic diagnostics without guest speech or credentials:

   ```sh
   adb logcat -v monotonic -s AudioTiming:I '*:S' > voice-timing.log
   ```

   Inspect `provider_session_identity`, `requested_asr_*`,
   `android_low_latency_playout_requested`, `provider_input_final_received`,
   `provider_thinking_state_received`, `sdk_non_silent_decoded_received_not_audible`
   and `rtc_stats`. Decoded audio is **not** first audible speaker output.
4. Fetch one existing synthetic test session with a staff-supplied environment
   credential; this command only GETs history and prints no transcript:

   ```sh
   python tools/fish_session_diagnostics.py --session-id SESSION_ID
   # Or analyze a previously saved history export without network access:
   python tools/fish_session_diagnostics.py --history synthetic-history.json
   ```

   The report groups only by explicit `turn_id`, flags missing/ambiguous replies,
   and calls timestamp differences `record_gap_ms`. It never fabricates ASR,
   LLM, TTS or speech latency from those records. The current public message
   schema contains no such spans; a `tool_result.latency_ms` is tool execution.
5. Run matched old/new synthetic recordings in both directions on the same
   hardware/network/voice. Use `tools/voice_latency.py` to score end-of-input
   speech to first **audible correct translation** on a single recorder clock.
   Include pauses, greeting overlap, interruptions, repeats and error recovery.
   Retain failures in the denominator. Compare pair-specific quality and p50/p95
   against Day <1.2 s/<2 s, plus the existing quality/echo/retry gates.

If the acoustic result still misses the target, use the RTC loss/jitter/buffer
observations and ask Fish for correlated ASR-final, LLM-first-token and
TTS-first-audio spans on that exact session. A history timestamp gap cannot
select which model to replace. Paid acceptance remains subject to the existing
verified spending allowance; no paid tests ran for this candidate.

## Verification

Baseline: 243 Python tests passed before editing. Candidate checks and their
results are recorded in [the verification record](verification/README.md).
Tests exercise the actual backend mint/end path with isolated SQLite databases
and a fake provider, all 2,652 distinct language-pair routing decisions, Android
configuration contracts, and read-only history handling. They do not certify
live recognition quality, echo cancellation, audible latency or full-duplex
hotel acoustics.

## Primary sources checked on 2026-10-21

- [Fish voice/language](https://docs.fish.audio/agents/build/voice-language): hosted ASR finalization, switching coverage, expressive delivery.
- [Fish configuration](https://docs.fish.audio/agents/build/configuration): response wait, max wait, speculation and opening echo guard.
- [Fish authenticated sessions](https://docs.fish.audio/agents/deploy/authenticated-sessions): nested per-session ASR override and metadata.
- [Fish OpenAPI schema](https://docs.fish.audio/api-reference/openapi.json): `AgentEchoGuardPatch`, `AgentSessionAsrOverridesPayload`, `PublicAgentLLMPatch`, `AgentSessionMessageItem`.
- [Fish wire protocol](https://docs.fish.audio/agents/deploy/protocol): media tracks, transcription channels and states.
- [Fish history](https://docs.fish.audio/agents/monitor/conversation-history): record timestamps, turn IDs and tool timing semantics.
- [Pinned LiveKit 2.29.0 source](https://github.com/livekit/client-sdk-android/blob/v2.29.0/livekit-android-sdk/src/main/java/io/livekit/android/dagger/RTCModule.kt): builder customizer invocation; checkout `8875d695e59e03721b08cbc190a2681ce643d857`, WebRTC `144.7559.14`.
- [WebRTC Java audio builder](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/audio/JavaAudioDeviceModule.java): disabled-by-default low-latency option.
- [Android AudioTrack.Builder](https://developer.android.com/reference/android/media/AudioTrack.Builder#setPerformanceMode(int)): low-latency mode is a platform request.
- [Deepgram multilingual code switching](https://developers.deepgram.com/docs/multilingual-code-switching): multilingual streaming differs from monolingual language detection.
