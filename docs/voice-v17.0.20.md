# Voice configuration update: 17.0.20

Historical conversation-first configuration record. This version used direct Fish without backend enrollment. It is not a current install, qualification or credential-rotation instruction; follow [the current distribution contract](ENVIRONMENT.md).

## Changes recorded at the time

- Full Android agent configuration was hashed and cached configuration moved
  to revision 9, avoiding reuse of the earlier expressive=false identity.
- Fish S2.1 Pro and expressive delivery were selected. The prompt requested
  natural interpretation without filler, laughter or commentary; this was
  configuration, not human naturalness qualification.
- Response wait moved from 450 ms to 300 ms: 150 ms less configured waiting,
  not a measured audible improvement. Fish could still extend an incomplete turn.
- Staff language selection used the 52-code allowlist instead of a seven-code
  English fallback. That did not qualify all 52 languages acoustically.
- Scribe v2 Realtime and multilingual input/interruption were retained;
  speculation was disabled after duplicate-response recovery. Later versions
  changed settings, so this is not current speculation or recognizer advice.
- Managed configuration was aligned for a future deployment; no backend was
  deployed by this update.

## Security limit

This historical standalone APK contained an extractable provider credential,
not secure production credential storage. The former instruction to delay
rotation until a keyless deployment is withdrawn. The known public 17.0.37
personal-testing APK also exposes a disposable Fish key; its account owner
must rotate/revoke it after testing. A key-free hotel build cannot revoke old
copies. Follow [current signing and publication guidance](ENVIRONMENT.md#signing-and-publication).

## Qualification boundary

Do not install this historical version as the current candidate. Follow
[TEST-PLAN.md](TEST-PLAN.md#voice-repair-reproduction-unreleased) with the exact
current artifact, held-out inputs, repeated trials, both directions,
common-clock capture and bilingual review. No physical acoustic/provider
acceptance was claimed by these local checks. Current hardware, entitlement,
remaining aggregate budget and human-review gates remain blocked as described
in [the pilot prerequisites](PILOT-DEPLOYMENT.md).

## Sources

- https://docs.fish.audio/agents/build/voice-language
- https://docs.fish.audio/agents/build/configuration
- https://docs.fish.audio/agents/deploy/authenticated-sessions

## Historical local validation, not current qualification

- Full Python suite: 167 passed (one existing AnyIO deprecation warning).
- Android unit suite: 54 passed, zero failures.
- Android lint: zero errors, 18 existing warnings.
- Debug APK assembly: successful; version 17.0.20, code 50.
- Review fixed stale-cache reuse, the obsolete seven-language fallback, and automatic refresh for future configuration edits by including the full config in Android's cache hash.
- No UI changes or mock screenshots. No provider session or paid speech test was started from the development environment.
