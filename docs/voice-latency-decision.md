# Live-translation latency: historical decision and current evidence boundary

The 17.0.22 investigation considered staying on managed Fish before a custom
pipeline. Its speculation-off/revision-10 instructions were superseded; its
provider-history and tier assumptions were not acoustic measurements. They are
not active deployment or tuning steps.

## What the historical evidence can establish

- `response_wait_ms` and `response_max_wait_ms` are configured waiting limits.
  They cannot establish actual audible delay or how much delay is removable.
- A vendor partial-transcript figure is not hosted finalization time. Neither
  identifies the recognition contribution on the tablet without matched traces.
- `tool_result.latency_ms` measures tool execution. The published history
  message schema does not provide per-message ASR/LLM/TTS stage spans. Session
  timestamp differences are record gaps, not acoustic speech-to-speech latency.
- Standalone S2.1 Pro/Free benchmarks do not identify a managed agent's synthesis
  infrastructure or prove that upgrading a tier saves time in this app. No
  synthesis-tier change is prescribed.
- One configured output language and prompt-level per-utterance identification
  do not prove correct bilingual recognition, translation or direction.

The old instructions to obtain per-message `latency_ms`, switch to Pro as the
largest latency win, keep speculation off, and force Scribe/revision 10 are
withdrawn rather than retained as a contradictory action list.

## Current route decision

Keep `VOICE_ROUTE=fish` as the managed default and preserve explicit personal
direct Fish testing. Gemini 3.8 Flash, Fish S2.1 Pro, the selected voice, current
pair-dependent Nova-3/Scribe recognition, speculation enabled, response wait
300 ms and max wait 600 ms remain unchanged by this qualification repair.
Browser, source and transport checks do not justify tuning those settings.
Self-hosted LiveKit remains a separately opted-in
[bounded evaluation route](selfhosted-voice-route.md), not a qualified faster
replacement or a fallback to a different provider.

## Required evidence before an improvement or deployment claim

Use [docs/TEST-PLAN.md](TEST-PLAN.md#voice-repair-reproduction-unreleased) and
the calibrated `tools/voice_latency.py` analyzer. For each intended pair, retain
at least 100 distinct held-out hotel utterances, repetitions, both directions,
common-clock input/speaker captures, bilingual meaning/naturalness review,
critical-entity denominators and scheduled failures/timeouts. A detector output
or transport timestamp is not a human-reviewed correct audible translation.

Day acceptance requires p50 <1.2 s and p95 <2 s, at least 95% successful
conversations, at least 99.5% critical-entity preservation, below 1% self-echo,
below 2% failed/retried turns, and interruption <150 ms where supported.
Night p50 <1.5 s/p95 <2.5 s applies only to an actually enabled night voice
route; current microphone-off staff assistance is not one.

Capture a pinned hosted baseline and the exact candidate on the same isolated
matching tablet/network/voice before claiming improvement. Without a comparable
baseline, report absolute acceptance only when actually measured, never an
estimated improvement. Current baseline/candidate physical captures, confirmed
provider entitlement, bilingual review and designated hotel staff are missing.
The remaining portion of the historical aggregate $4 allowance and a
conservative provider-cost reservation have not been established: run no paid
call, including Gemini drafts or answers. These provider, physical and staff
gates are blocked; source correctness does not pass them.

## Sources

- https://docs.fish.audio/agents/build/configuration
- https://docs.fish.audio/agents/build/voice-language
- https://docs.fish.audio/agents/monitor/conversation-history
- https://benchmarks.coval.ai/models/s2.1-pro
- https://elevenlabs.io/realtime-speech-to-text-api
