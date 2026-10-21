# Voice configuration update: 17.0.23

Latency-focused configuration revision. No backend, pairing, or interface changes; an existing standalone install updates in place.

## Changes

- `speculative_response` is now on. Fish drafts the interpreter reply during the response wait instead of adding that wait to every reply; the draft is discarded if the caller keeps speaking, and tools never run early (tools are disabled here anyway). Documented effect: removes the response wait from each reply, up to about 300 ms per turn, more when the eager turn detector stretches the wait.
- `response_max_wait_ms` is set to 600. Previously unset, Fish's automatic ceiling was the wait plus 500 ms; now the "caller may still be speaking" extension is capped at +300 ms. Saves up to 200 ms on turns where the provider extends the wait.
- `response_wait_ms` stays at 300, the documented minimum.
- Fish configuration revision moves 10 to 11 in the app so existing installs re-provision the agent with these values. The cloud route mirrors the same conversation values and its revision moves 9 to 11.
- Pre-change baseline from a real tablet session (Fish session records, 2026-10-21 evening IST): the gap between a guest message and the interpreter's reply was 2.1 s, 3.2 s, 2.4 s and 2.1 s (median about 2.4 s). Those are record timestamps; real speech-to-speech latency also includes endpointing and playback start. These config changes attack the wait portion, not LLM generation or synthesis, which remain the larger share.

## Regression watch

`speculative_response` has history: it was switched off in 17.0.20 after an observed duplicate response that followed provider-error recovery. Provider docs say a speculative draft is discarded when the caller continues speaking, but the error-recovery path is exactly what to watch. If a duplicated or stale reply ever appears after an error on the tablet, the revert is a configuration revision bump, not new code. Tablet acceptance decides whether this stays.

## Acceptance on the tablet

Update to 17.0.23, end any existing conversation, then start a new one (the agent is re-provisioned by the revision bump). Compare against 17.0.22: replies should begin noticeably sooner after speech stops. Watch specifically for any repeated or doubled reply after a network hiccup or recovery banner. Local tests cannot prove actual speech timing, acoustics, or provider behavior; no physical-device acceptance is claimed.

## Local validation and review

- Full Python suite: 241 passed (Python 3.12).
- Android unit suite: 84 passed, including the updated configuration assertions (wait 300, max wait 600, speculation on).
- No JavaScript or caption changes, so the browser fixture is untouched from 17.0.22.
- No mock screenshots were produced as device proof; the tablet owner runs acceptance via the APK.
