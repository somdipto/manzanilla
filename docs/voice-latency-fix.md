# Voice latency investigation: historical 17.0.25 candidate

Investigated on 2026-10-21 from `main` commit
`8b7c006b53598f275b73c171a6d44b5f646f923d` (17.0.24/code 54).
The historical candidate was 17.0.25/code 55, Fish configuration revision 12;
these are not current installation or provisioning instructions.

The implementation changed settings associated with provider finalization and
opening speech handling. It did **not** measure removal of tablet delay or
prove that the acoustic target was met. The investigation had no Fish
credential, real session trace or attached reception tablet. No provider call
was started and no production agent was edited.

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

This historical routing change followed documented provider finalization
figures on eligible pairs, not matched tablet measurements. It proves neither
equal bilingual accuracy nor an acoustic improvement. Qualification must check
accented speech, names, negation, numbers, dates and currency in each intended
pair. Browser or transport checks alone do not authorize a recognizer change;
any proposed rerouting needs a separate evidence-backed decision.

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

## Current reproduction boundary

Use the current exact signed candidate and [artifact/distribution contract](ENVIRONMENT.md#signing-and-publication),
not the old revision-12 candidate above. A debug artifact is key-free and must
not replace the user's signed installation. Updating source does not install
an APK or deploy a backend. Preserve current Gemini/Fish models, selected
voice, recognizer routing, speculation and endpointing during qualification.

Before any paid call, establish remaining authorization within the original
aggregate $4 allowance and a conservative cost reservation. Neither is
available for this repair, so live provider and physical acceptance are
blocked. The isolated matching tablet/capture, current provider entitlement
and bilingual review are also missing. No provider substitution or renewed
allowance is authorized.

When those prerequisites are actually satisfied, use an isolated property,
the exact artifact and the [acoustic protocol](TEST-PLAN.md#voice-repair-reproduction-unreleased):

1. Pin the hosted baseline and candidate identities/configurations. Keep the
   same hardware, network, language pair and voice; no comparable baseline
   means no measured improvement claim.
2. End the prior conversation before starting the controlled candidate trial;
   record its actual revision and requested pair/model rather than forcing
   historical revision 12. Managed APK and backend identities are distinct.
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
5. Capture measured input and speaker output on one calibrated recorder clock;
   score end-of-speech to first **correct audible translation**. Synthesized
   provider input in a standalone pipeline bench is not a physical baseline.
   Retain at least 100 distinct held-out hotel utterances per intended pair,
   repetitions and both directions, bilingual meaning/naturalness review,
   critical-entity denominators and every scheduled failure/timeout.
   Include startup/greeting overlap, long-synthesis interruption, repeated
   speech, unclear words, names/room numbers/negation/prices/dates,
   mute/unmute/rapid stop-start, network loss, expired initial token, reconnect
   and credential revocation. Self-hosted evaluation additionally requires
   refreshed-token and room-replay tests.

Compare absolute results against the full Day quality, echo, retry and latency
gates in TEST-PLAN, not just successful-only p50/p95. If a measured result
misses a target, use RTC diagnostics and request correlated provider spans
for that exact session before attributing a bottleneck. A history timestamp
gap, tool latency or configuration ceiling cannot select a model to replace.
No paid acceptance run or measured improvement is established here.

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
