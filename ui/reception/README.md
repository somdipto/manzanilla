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
