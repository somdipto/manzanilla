# Voice configuration update: 17.0.20

Conversation-first update. The direct Fish route remains in this version; no backend or pairing is required for an existing standalone install.

## Changes

- Hash the full Android agent configuration and refresh cached Fish configuration (revision 9), so old expressive=false agents are no longer reused.
- Explicit Fish S2.1 Pro synthesis with expressive delivery. Prompts request clear, natural interpretation without added filler, laughter, or commentary.
- Reduce Fish response wait from 450 ms to its documented 300 ms minimum. This is 150 ms less configured waiting, not a measured end-to-end improvement. Fish can wait longer when speech seems incomplete.
- Use the staff-selected language across Fish's documented 52-language allowlist, rather than silently falling back to English outside seven languages.
- Keep Scribe v2 Realtime recognition, multilingual input, interruption, and speculation disabled. Speculation previously coincided with duplicate-response recovery; it is not safe to enable without acoustic regression testing.
- Keep managed configuration aligned, including localized guest greetings, for a future deployment. This release does not deploy that backend.

## Security limit

The standalone public APK still contains the provider credential. It is extractable. This is a temporary known risk, not a secure production design. Do not rotate it after this update: this version still needs that credential. Rotation belongs after a keyless backend release is deployed and devices have migrated.

## Acceptance on the tablet

Update to 17.0.20, end any existing conversation, then start a new one. Compare end-of-speech to first audible translation, naturalness, language switching, interruption, and exact numbers/names in both directions. Local tests cannot prove actual speech quality, hotel acoustics, provider entitlement, or real-device latency. No physical-device acceptance is claimed.

## Sources

- https://docs.fish.audio/agents/build/voice-language
- https://docs.fish.audio/agents/build/configuration
- https://docs.fish.audio/agents/deploy/authenticated-sessions

## Local validation and review

- Full Python suite: 167 passed (one existing AnyIO deprecation warning).
- Android unit suite: 54 passed, zero failures.
- Android lint: zero errors, 18 existing warnings.
- Debug APK assembly: successful; version 17.0.20, code 50.
- Review fixed stale-cache reuse, the obsolete seven-language fallback, and automatic refresh for future configuration edits by including the full config in Android's cache hash.
- No UI changes or mock screenshots. No provider session or paid speech test was started from the development environment.
