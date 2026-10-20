# Voice latency investigation plan

## Request and scope

> The model is good, the translation is good, and the text-to-speech is also good. The three layers are good but why are we using LiveKit? Why is the latency very high? We need to figure out properly how we can make the latency much better. Can you write a proper plan on what we should do to figure out how to make the latency much better?

Plan only. Preserve Scribe v2 Realtime, Gemini 3.8 Flash, Fish S2.1 Pro, the voice, expressive delivery, multilingual support, faithful translation, interruption, and the reception UI. No model swaps, provider mutations, paid calls, builds, pushes, or releases are authorized by this plan request. Preserve existing working-tree changes. This document is the planning artifact, not evidence of a latency improvement.

## What LiveKit does, and why it is here

Fish's [hosted-agent protocol](https://docs.fish.audio/agents/deploy/protocol.md) returns `transport: livekit`, a room URL, and a participant token. The Android app joins that room, publishes microphone audio, and subscribes to agent audio. Audio uses WebRTC media tracks; captions and control events use separate LiveKit mechanisms. LiveKit is not another inference model.

The current client validates this transport in `FishVoice.parseSession`. `FishVoice.connect` uses LiveKit Android 2.29.0 (`android/hotel/build.gradle.kts`), configures hardware echo cancellation/noise suppression, and calls `setMicrophoneEnabled(true)` after room connection. `HotelActivity.connect` selects Fish for translation. The optional hotel backend mints a session; it does not relay microphone audio through `cloud/fish.py`.

Keeping this hosted Fish agent means keeping its supported transport unless Fish supplies another documented option. Replacing it with a standalone ASR/LLM/TTS pipeline would be an architecture change requiring our own streaming, turn detection, interruption, echo handling, session ownership, and recovery. It is not the first latency fix. LiveKit could contribute network/jitter/playback delay, but no current measurement establishes its contribution.

## Verified configuration and remaining unknowns

Sources: `FishPlan.agentConfig`, `FishPlan.sessionBody`, `FishVoice`, `AudioTurnTiming`, `cloud/fish.py`, and `docs/voice-v17.0.20.md` at checkout `b8bd442`.

- ASR: `elevenlabs:scribe_v2_realtime`; LLM: `google/gemini-3.8-flash`; TTS: `s2.1-pro`.
- Response wait: 300 ms. The [Fish configuration contract](https://docs.fish.audio/agents/build/configuration.md) says this is the minimum; incomplete speech may wait longer. The unset maximum follows wait + 500 ms, nominally 800 ms here; do not treat this as a measured total bound.
- Speculative response is false after historical duplicate output during provider recovery. Interruptible is true, sensitivity balanced, tools and knowledge lookup disabled.
- The microphone opens at room-ready. The obsolete local greeting-WAV gate is gone. Startup and greeting timing still need measurement separately from later turns.
- Agent configuration revision 9 hashes the full configuration. Verify the actual published version and session overrides before comparing anything; the installed tablet and managed backend deployment are not established by this checkout.
- Fish documents a channel-specific echo guard that can drop caller speech during the first three seconds of agent speech. It is not explicitly set here. Confirm the effective channel/configuration; do not assume this default applies or silently disable it.
- Current timing logs sample microphone activity every 50 ms and mark decoded SDK audio, not speaker output. The decoded callback may contain silence. A provider-final event is a client receipt, not the server's ASR processing timestamp.
- Fish explicitly paces agent transcription to audio playback. Caption arrival is not LLM first-token time. Sticky `thinking`/`speaking` states are coarse observations, not provider spans.
- `adb devices -l` returned no attached devices during planning. Existing 4.90/7.22-second historical caption gaps in `artifacts/VERIFICATION.md` are not a current acoustic baseline or a causal diagnosis. The provider wallet snapshot there does not establish remaining paid-test budget.

## 1. Build the measurement loop before fixing latency

Execution prerequisite: an isolated test tablet matching the reception unit's hardware/audio route, or owner-approved supervised testing on the target tablet with synthetic conversations and isolated configuration. Never mutate a real guest session or live hotel data. An emulator can check event ordering, not hotel acoustic latency.

First record APK version/code, configuration hash/published version, session overrides, voice, language pair, SDK version, device/OS, speaker route/volume, network, and test location. Keep API keys, JWTs, auth headers, real guest speech, and identifying network details out of evidence. Use only synthetic hotel utterances and a disposable acceptance agent/session; creation/publication and any paid tests require explicit approval.

Build a small replay/capture runner around the actual Android path, not a replacement pipeline:

1. Play a fixed synthetic phrase into the tablet microphone. Use a stereo external recording with one reference channel for the input and one for tablet speaker output. Calibrate leakage/crosstalk and acoustic path delay; fix placement and volume across runs. Validate detected boundaries by listening and inspecting a few waveforms.
2. Timestamp the end of the input phrase and first audible translated speech on the same recorder clock. Exclude the opening greeting from ordinary-turn latency; separately test speech during the greeting for lost input. Count the first sound only if it is the expected translation, not echo, a greeting, or an error message.
3. Collect session-scoped local monotonic timing events and available WebRTC statistics alongside the recording. Add isolated instrumentation only where existing events cannot locate a boundary. Verify APIs exposed by SDK 2.29.0 before implementing collectors.
4. Replay identical phrases and detect/score boundaries consistently. Keep overlap/interruption trials separate from serial latency trials. Do not infer input/output association from nearest caption timestamps. For serial trials allow one outstanding utterance and retain the known fixture ID; ambiguous/multiple outputs are failures, not paired successes.
5. Produce a sanitized turn table: fixture/run ID, direction, cold/warm condition, input end, audible onset, total latency, success/error/timeout, quality result, recovery/reconnection, and available transport metrics. Retain raw synthetic evidence privately and reference its digest.

The runner and analyzer do not exist yet. First implementation deliverable is their exact invocation, sample sanitized output, and a red result reproducing the slow experience. Validate the analyzer on a known-offset offline recording before trusting live timings. If there is no approved device/capture setup or no reproducible slow sample, execution is BLOCKED; do not tune from guesses.

### Timing boundaries and their limits

| Boundary | Source | What it proves |
| --- | --- | --- |
| Start tap → session response → room connected → microphone enabled | New local monotonic markers around existing calls | Startup/setup duration, separated from steady-state turns |
| Input end → audible translation onset | One calibrated external recording clock | Actual guest-visible response latency |
| Input activity estimate → final transcript receipt | Existing activity and transcript callbacks | Client-observed composite interval, not pure ASR or turn detection |
| Agent state/transcript updates | LiveKit attributes/text streams | Ordering and recovery clues, not LLM generation timing |
| First non-silent decoded audio → speaker onset | SDK sink plus aligned external capture | Output-side delay only after clock alignment and measurement uncertainty are established |
| RTT, loss, jitter, jitter-buffer delay, concealment, candidate/relay type | SDK/WebRTC statistics when available | Transport symptoms; report missing fields, do not substitute RTT for one-way or acoustic latency |
| Provider endpointing, ASR final, first LLM token, first TTS audio | Fish-provided session trace/support export if available | Stage attribution only if documented timestamps, clock domain, and correlation are supplied |

Do not subtract recorder timestamps from Android timestamps without a measured alignment/error bound. Do not sum independent medians as total latency: stages can overlap and turn detection can revise decisions. If Fish cannot expose internal spans, report the opaque hosted segment as a composite, not invented per-model numbers. The [history API](https://docs.fish.audio/agents/monitor/conversation-history.md) provides message timelines, not documented per-model latency spans; audio-off still retains transcripts.

## 2. Establish the baseline cheaply

Start with English ↔ Spanish, the same ten short synthetic fixtures in each direction, across three warmed repetitions: 60 serial turns. Cover short complete statements, deliberate pauses, negation, spelled names, room numbers, dates, and currency. Add a smaller explicitly separate cold-start/greeting sample and interruption sample. Freeze recordings and expected meanings before A/B tests. Expansion to other languages follows, not a claim of all-language acceptance from Spanish tests.

Keep the same device, volume, network, voice, prompt, models, input audio, phrase spacing, and warm/cold category. Record all failures and a predeclared 15-second response timeout; never drop timeouts from the report. Qualify latency over **all scheduled trials**, using nearest-rank percentiles: `sorted(values)[ceil(p*n)-1]`. A timeout, wrong translation, duplicate output, provider error without a valid response, or other unsuccessful turn scores 15 seconds for this threshold calculation, while its failure category remains separate. This is a censored qualification score, not a claim that failed audio arrived at 15 seconds. Report successful-only latency distributions solely as conditional diagnostics, alongside all-trial scores and failure rates. Show each direction separately. Pilot p95 is descriptive, not final acceptance.

Before any paid call, establish a conservative maximum session charge and reserve against the verified remaining portion of the historical $4 total cap, including prior/pending usage. If pricing or remaining spend cannot be bounded, stop. This plan spends $0. If 60 turns or later qualification cannot fit, retain partial evidence and mark that gate BLOCKED; do not reduce the required acceptance sample and call it passed.

## 3. Locate the largest delay, then change one thing

Rank hypotheses only after baseline evidence. Run paired, interleaved A/B trials (balanced AB/BA order) against the same fixtures and conditions. Pin immutable baseline/candidate acceptance-agent versions; end sessions after each batch. One sequential owner changes shared agent configuration/instrumentation. Do not alter the user's active agent.

| Evidence | Next experiment, retaining all three models | Required proof / stop condition |
| --- | --- | --- |
| Delay only at first connection or first greeting | Split agent lookup/create/publish, ticket creation, room join, and capture timings; compare cached vs uncached setup | Faster setup without opening an idle paid microphone session; no claim about later turns |
| Long pauses or spelled numbers trigger extra waiting | Obtain effective endpointing/max-wait/echo-guard behavior; ask Fish for traces. Only then compare one documented turn control | Faster response without cutting off pauses, losing digits, or dropping greeting-overlap speech. 300 ms cannot be lowered through the documented API |
| Large RTT/loss/jitter, relay fallback, or rising buffer delay | Compare approved stable Wi-Fi vs alternate controlled connection; inspect selected ICE path and ask Fish about media AND agent/model region placement for Spain | End-to-end improvement on comparable trials; distinguish media edge from model region. No assumed Europe-region knob or forced UDP workaround |
| Large decoded-to-audible delay or stalls under load | Inspect audio route, CPU/main-thread load, buffering, and audio callback work; test one supported SDK setting/fix in an isolated build | Lower acoustic delay without clipping, underruns, echo, missed interruption, or ownership leaks; do not disable AEC/noise suppression blindly |
| Most delay remains in opaque hosted processing | Obtain Fish stage spans and recovery counts; ask whether audio/text streaming is buffered internally and whether existing-model routing can be improved | Same models/voice/faithfulness; first-audible measurement must improve, not just captions. Mark unknown spans unknown |
| Evidence suggests waiting before generation dominates | Consider speculation only in a disposable A/B after reproducing the historical duplicate/recovery case | No duplicate or stale spoken output, including continuation and recovery trials. Reject immediately on regression |

Do not shorten the translation, remove clauses, swap recognizers, or hide the listening state to manufacture a faster result. A text-in/audio-out diagnostic bypasses microphone/ASR/endpointing but still has its own scheduling; label it a localization control, not an equivalent speech trial or an additive decomposition.

## 4. Decide whether LiveKit should stay

Default: retain it until measurements show an actionable transport bottleneck. Network quality may implicate a route rather than the LiveKit library itself.

If evidence still implicates transport after supported fixes, ask Fish for a supported alternate transport for the SAME hosted agent/models and measure a controlled A/B. If none exists, there is no honest drop-in comparison. A custom streaming pipeline is a separately approved prototype, with explicit scope and funding, and must re-prove interruption, echo, fidelity, recovery, and language coverage. Reject an architecture rewrite justified only by the label “WebRTC.”

## 5. Workstream graph and independent gates

| ID / owner boundary | Artifact / files if implementation is approved | Depends on | Reproduction / integration impact |
| --- | --- | --- | --- |
| M: measurement owner | Isolated replay/capture analyzer; minimal markers in FishVoice/AudioTurnTiming | Device, synthetic fixtures, approvals | Offline known-offset calibration, then Android acoustic baseline; defines shared evidence schema |
| N: transport investigator | Sanitized WebRTC stats and network/region report; no agent edits | M's schema and baseline | Same fixtures on controlled networks; helps classify transport contribution |
| P: provider investigator | Effective config/version and available stage traces; no live-agent edits | M's schema, authorized isolated session | Match immutable config and synthetic run IDs; name unavailable spans |
| F: sequential fix owner | Only code/config implicated by M/N/P | M + relevant N/P results | Paired A/B recording and quality results; shared contract changes rerun downstream evidence |
| I: integration critic | Complete evidence bundle and candidate artifact | M/N/P/F | Fresh blind evaluation on held-out fixtures and the actual tablet path |

N and P can run independently once M's schema exists. Each failed round returns one largest evidenced gap and one next proof; the fix owner does not grade acceptance. A fresh critic receives this request, frozen criteria, baseline/candidate recordings, commands, and artifacts, not the builder's rationale. Include raw failures and missing data. A fresh final integration critic is required even if component checks pass.

### Acceptance and stop rules

Existing product bar from `docs/TEST-PLAN.md`: Day p50 <1.2 s / p95 <2 s, ≥95% successful conversations, ≥99.5% critical-entity preservation, <1% self-echo turns, <2% failed/retried turns, and interruption <150 ms where hardware supports it. No Night workflow expansion is in this work.

Final qualification retains the existing at-least-100-distinct-utterance corpus, repeated trials, both directions, interruptions, bilingual human review, and names/numbers/dates/currencies. Predeclare denominators for conversations, entities, echo opportunities, and interrupted turns. Pilot counts cannot establish rare-error thresholds. Treat final sample/funding gaps as BLOCKED, not waived.

Additional investigation comparison rule is **DERIVED — confirm before running**: use paired bootstrap 95% intervals for candidate-minus-baseline all-trial p50 and p95 in each direction. Resample matched fixture/repetition blocks, retaining both arms and every failure score; never complete-case-filter failed turns. The p50 interval's upper bound must be below zero by more than calibrated measurement uncertainty; the p95 interval's upper bound must be ≤0. If an arm was never run or capture was technically invalid, the comparison is BLOCKED until both arms of that block are rerun under the frozen protocol; preserve the invalid records. A valid run with no response is a scored failure, not missing data. Noisy/inconclusive results need an approved larger sample rather than a claim of improvement. All existing fidelity/safety gates still apply; subjective voice naturalness must win or tie in blind bilingual review. Freeze this rule before spending, not after seeing results.

Offline regression for the qualification population (run with `python3`; no provider/device required):

```python
from math import ceil

def score(value):
    return 15.0 if value is None else value

def percentile(values, p):
    return sorted(values)[ceil(p * len(values)) - 1]

turns = [1.0] * 940 + [2.5] * 45 + [None] * 15
conditional = percentile([x for x in turns if x is not None], .95)
all_trials = percentile([score(x) for x in turns], .95)
assert conditional == 1.0 and all_trials == 2.5
assert not (all_trials < 2.0)
pairs = [(1.0, None), (None, 1.0), (2.5, 2.5)]
scored_pairs = [(score(a), score(b)) for a, b in pairs]
assert scored_pairs == [(1.0, 15.0), (15.0, 1.0), (2.5, 2.5)]
print("trials=1000 conditional_p95=1.0 all_trial_p95=2.5 Day=FAIL")
print("paired_failures_retained=3")
```

`None` here means a completed trial with an unsuccessful response, not an unexecuted arm. A bootstrap must draw whole indexed blocks from `scored_pairs`, never split or delete either arm.

- PASS (latency): held-out acoustic evidence meets every applicable existing gate, the confirmed comparison rule, and fresh integration review. A plan review is not this PASS.
- BLOCKED: no isolated device/acoustic capture, unavailable required evidence, unknown safe remaining spend, insufficient acceptance funding, or missing authority. State exactly what can resume work.
- PLATEAU: two materially different, evidence-producing strategy resets fail to move the measurement; preserve best-known state and request the provider/architecture decision. If the same prescribed fix worsens the same metric twice, reject it first and use a fresh diagnostician.
- Human stop: credential exposure, real guest data, uncontrolled spend, production-agent mutation, deployment, or a scope change. No public release or budget increase without authorization.

## Planning result and next action

Observed during planning: source/primary-document inspection, an empty attached-device list, and the offline failure-aware percentile regression above. No acoustic baseline, provider call, model experiment, or latency improvement is claimed. Workbench request, contract, graph, and resume instructions live in this file; critic rounds are recorded in the session.

Next action after plan approval: implement and calibrate M on an isolated matching tablet/capture setup, establish a red acoustic baseline, then request only the bounded provider diagnostics needed to explain its largest gap. No model replacement and no LiveKit removal before that evidence.
