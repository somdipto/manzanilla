# Android startup and update delivery (17.0.28)

The installed 17.0.24 APK is the last published release as of the initial check
on 2026-10-22. PR #5 merged into main and passed verification, but the existing
publication workflow only ran for a version tag or manual dispatch. Neither a
merge nor a debug build artifact made a newer APK available to installed devices.

## Reproduced startup failure

A disposable Android 10/API29 emulator with its stock Android System WebView
74.0.3729.185 loaded the prior APK assets and reported:

```
Uncaught SyntaxError: Unexpected token ? — language-turns.js:15
Uncaught SyntaxError: Unexpected token ? — reception.js:77
```

The scripts used optional chaining and nullish coalescing unsupported by that
WebView. `Object.hasOwn` also aborted startup on browsers older than Chromium93.
The earlier browser fixtures ran Chromium154 and did not establish Android
compatibility. The JavaScript/native bridge names were checked and match; a
bridge naming mismatch was not the cause.

JavaScript is now authored in `ui/reception/` and compiled with pinned esbuild to
Chrome61 syntax, with unsupported runtime helpers removed. Generated files are
committed under the existing Android asset paths. Run `npm ci` and
`npm run build:reception` after changing source; CI rejects stale assets with
`npm run check:reception`. CSS includes older-WebView layout fallbacks. Native
startup verifies the receiver exists and shows a recovery message if scripts
fail instead of leaving an inert page.

Start Translation explains why it is disabled: the two selected languages are
the same, audio has not been configured, or microphone release is pending.
Staff setup stays reachable from language selection. A different language is
not silently selected for the user.

## First microphone permission

The emulator reproduced a separate lifecycle failure: the system permission
dialog pauses the activity, which previously stopped the proposed session and
cleared `pendingPermission`. Granting access could therefore never continue the
translation. The app now retains only this pending, microphone-off request
through the dialog, consumes a grant once the activity resumes, and cancels it
when the activity actually stops in the background. Existing sessions still
release audio on pause.

## Notes and appearance

The note UI changes from 17.0.27 remain included. Six additional native
HotelGateway tests use actual OkHttp HTTPS requests to an isolated loopback
server. They cover form/review/exact Unicode text, confirmation, cancellation,
retrying the same confirmation, malformed replies, and suppressing callbacks
from an ended session. No requests are made to a live hotel service. Saving a
note requires the hotel service URL and device credential; a Fish key alone is
not a hotel message-delivery backend.

Day/night appearance remains separate from after-hours reception. The compiled
UI and compatibility styles retain the existing browser palette/contrast tests.

## Android tap checks

The installed key-free APK was also driven using `adb input tap`, with targets
located from the rendered DOM tree and native WebView bounds. The stock
WebView did not expose HTML descendants through UIAutomator. Screenshots in
`docs/screens/17.0.28-*-emulator.png` show the actual Android rendering.

Translation setup, Back/End navigation, day/night appearance and independent
after-hours mode respond. The missing-audio reason appears beside Start.
Message staff and arrival support reach an explicit missing-hotel-backend
result on this unconfigured device. Staff setup correctly explains that a
screen-lock credential must be enrolled before administrator authentication.
The isolated HTTPS and browser fixtures cover the configured note workflow;
these unconfigured emulator checks do not establish delivery to a real hotel.

## Release delivery

A main-branch merge changing `android/VERSION` or `android/VERSION_CODE` now runs
`Publish verified Reception APK`. It verifies source and the Android runtime,
builds with the existing signing key, checks package/signing/version continuity
against the published APK, creates or verifies the exact immutable version tag,
and publishes the APK and release metadata together. PR builds do not publish.
A pre-existing tag for a different commit is rejected, never moved.

The app can offer the update only after that publication workflow succeeds.
Android still asks the user to approve installation; source merges cannot
silently replace an installed app.

## Scope

The emulator uses software CPU/GPU emulation. For screenshot/touch QA, its
WebView was launched with GPU rasterization disabled to avoid the emulator's
Chromium74 font-serialization rendering defect; this is not an APK setting. Its wall-clock timings cannot
qualify microphone-to-translated-audio latency on the target hardware. No live
Fish session, production guest-data write, or claim of a sub-second translation
result is part of these isolated checks. The latency changes included from
17.0.25–17.0.27 still need a bilingual acoustic measurement on the actual device.
