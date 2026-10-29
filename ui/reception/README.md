# Reception JavaScript

Edit these source files, then run `npm ci` and `npm run build:reception` from the
repository root. Commit both the source and generated Android assets. CI runs
`npm run check:reception` and fails if generated files differ from their sources.
Android builds consume the checked-in generated files and do not require Node.

The Android minimum is API 26. Its initial WebView uses Chrome 61, so esbuild
transforms newer JavaScript syntax to that target. This does not polyfill runtime
APIs: use APIs available in Chrome 61 (for example `hasOwnProperty.call` instead
of `Object.hasOwn`, and plain loops instead of `Object.fromEntries`). Browser
fixtures and the Node language ledger tests exercise the generated assets.

The actual packaged HTML, CSP, native bridge and styles remain in
`android/hotel/src/main/assets/reception`. A real Android WebView test is still
required for touch behavior, rendering, permissions and lifecycle checks.

Caption placement uses only the current utterance's selected-language evidence.
Unclear (`und`) reports remain neutral after either speaker and store no translated
text. A late language annotation can move only its own legacy caption. Repeated
utterances retain distinct immutable IDs; explicit source references still pair
translations, and session/generation fences reject stale events.

Only recording uses microphone levels. Muted, off, stopping and unknown states
clear the level and all three bands; connecting never displays a listening glow.
Microphone and phase events update controls without replacing transcript nodes.

## Offline interface catalogue

Edit locale data only in `shared/reception-locales.json`. It owns all 52 offered
codes in keyboard order, English/native names, direction and complete interface
messages. English source strings are message IDs; interpolate only named plain-text
values such as `{id}`. Never translate an original guest message, identity, room,
receipt ID or hotel-authored policy as interface copy.

The builder validates duplicate JSON keys, language/message coverage, nonblank
translations, directions and placeholder parity. It generates `locales.js`, its
Chrome61-compatible packaged asset, identical native JSON and `DeskLanguages.kt`.
`--check` compares every generated output without writing. Do not edit generated
copies. The native JSON is not a WebView resource; only `/locales.js` is allowlisted.

Backend `cloud.localization.ui_text` and native `UiText.text` use the same cached
catalogue. Invalid saved locales select Spanish; an unexpected missing message or
substitution renders the selected locale's complete unavailable message, never
English or a raw exception. Native code preloads before voice callbacks.

Interface translations are model-authored and currently lack human review in
all 52 locales. Schema/placeholder checks and runtime lookup do not establish
meaning, naturalness or provider/tablet speech qualification.
