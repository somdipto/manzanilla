# Live-translation latency: decision (17.0.22)

> Historical decision, superseded by 17.0.23's speculation settings and the
> [17.0.25 latency investigation](voice-latency-fix.md). Two claims below are
> incorrect for the hosted route: Scribe's partial-transcript speed is not
> Fish's documented ~0.9 s finalization interval, and the documented history
> message schema does not provide a per-message `latency_ms` stage breakdown.
> `tool_result.latency_ms` measures tool execution. The Pro/Free TTS comparison
> does not establish which synthesis infrastructure a managed agent uses.

Scope: the guest-facing speech-to-speech delay on the tablet (end of one person's speech to first audible translation for the other). This document records the researched options and the decision. Local measurement continues with tools/voice_latency.py plus provider session records; end-to-end audible numbers come only from the tablet.

## What the evidence says

- Fish response_wait_ms is already at its documented floor of 300 ms (range 300-2000, default 550; the provider can extend the wait up to 500 ms more when speech looks incomplete). There is no more wait to remove on the current route.
- speculative_response stays OFF. Enabling it previously coincided with duplicate-response recovery; Fish documents that disabling it adds the full wait to every reply, so it is a correctness-versus-latency trade we do not take without acoustic regression testing on the tablet.
- Synthesis tier dominates the remaining budget: independent benchmarks put S2.1 Pro TTFA at mean ~349 ms (p50 ~319, p95 ~546), while the free tier of the same model averages ~839 ms. Which tier the tablet's credential gets is unverified and is the single largest known unknown.
- Recognition is Scribe v2 Realtime (~150 ms partials vendor figure); STT is not the bottleneck on this route.
- Fish's conversation-history API returns per-message latency_ms per session. That is the honest per-utterance measurement route on the real tablet sessions (needs the Fish API key, which only the owner can add to the vault).
- voice.speaking_language configures one conversation language per session; per-utterance language switching is prompt-level (revision 10) and unverified against real provider behavior.

## Decision

Stay on the managed Fish agent route (V1) and measure before any rebuild:

1. Owner adds the Fish API key to the vault so per-utterance latency_ms can be pulled from real tablet sessions. Without these numbers every further change is guesswork.
2. Owner confirms the credential's synthesis tier (Pro vs Free) in the Fish dashboard. If the tablet is on the free tier, moving to Pro is the largest single latency win available (~0.5 s mean TTFA) and costs nothing architectural.
3. Keep response_wait_ms=300, speculation off, Scribe v2 Realtime, per-utterance language identification (revision 10).
4. Re-measure after 1-3 with matched, repeated, whole-pipeline tablet runs (fidelity, negation and number checks must pass).

Rebuild trigger (V2, a custom streaming STT -> translation -> TTS pipeline): only if, after steps 1-3, the tablet's measured p50 speech-to-speech latency still misses the target in docs/voice-latency-plan.md. V2 trades the managed agent's echo handling and session management for self-owned orchestration; it is not justified before measurements show the managed route cannot reach the target.

Explicitly rejected: enabling speculative_response (correctness risk), lowering response_wait_ms below the documented floor (unsupported), switching providers before measuring (no matched evidence any alternative is faster end-to-end under hotel acoustics).

## Sources

- https://docs.fish.audio/agents/build/configuration
- https://docs.fish.audio/agents/build/voice-language
- https://docs.fish.audio/agents/monitor/conversation-history
- https://benchmarks.coval.ai/models/s2.1-pro
- https://elevenlabs.io/realtime-speech-to-text-api
