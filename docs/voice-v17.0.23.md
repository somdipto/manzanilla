# Voice configuration history: 17.0.23

Historical configuration record, not a current installation instruction or an
acoustic-improvement result. Use the current signed-artifact and distribution
contract in [README](../README.md), not an old version from this note.

## Configuration changes recorded at the time

- `speculative_response` changed to enabled. Fish documents drafting during the
  response wait and discarding the draft when the caller continues speaking.
  This describes provider behavior, not an observed saving on the tablet.
- `response_max_wait_ms` changed to 600 while `response_wait_ms` remained 300.
  A configured ceiling bounds one wait; it does not measure end-of-speech to
  first correct audible translation or imply a 200 ms improvement.
- Android revision 10 and backend revision 9 moved to revision 11. Those are
  historical identities, not the current configuration revision.
- Fish session-record timestamp gaps from 2026-10-21 evening IST were 2.1 s,
  3.2 s, 2.4 s and 2.1 s (median about 2.4 s). These are **non-acoustic record
  gaps**, not a comparable physical baseline, stage breakdown or evidence that
  LLM generation or synthesis dominates the tablet's delay.

## Regression and acceptance boundary

Speculation had been disabled in 17.0.20 after a duplicate reply associated with
provider-error recovery. That history motivates testing repeated/stale output
after network loss; it does not establish current safety or justify changing
the present speculation, recognizer, endpointing, model or voice settings.

The former instruction to install 17.0.23 and expect noticeably earlier replies
is withdrawn. No matched acoustic before/after result was recorded. Qualification
must follow [the acoustic protocol](TEST-PLAN.md#voice-repair-reproduction-unreleased):
held-out utterances, repeated trials, both directions, common-clock capture,
bilingual review and all scheduled failures retained. The isolated physical
tablet/capture setup, verified provider access and remaining aggregate $4
allowance are unavailable for the current repair, so provider and physical
acceptance remain blocked. No paid run is authorized by this historical note.

## Historical local validation, not voice qualification

The original development record reported 241 Python tests and 84 Android unit
tests, including configuration assertions. No JavaScript/caption changes or
device-proof screenshots were reported. These historical source checks are not
newly executed here and establish no audible latency, human meaning,
naturalness, provider entitlement or hotel acceptance.
