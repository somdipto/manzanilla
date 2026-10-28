# Personal Fish Audio setup

17.0.36 removes the extra Android device-credential prompt from personal
translation setup. The translation button and its settings gear open the
Fish-only key screen directly, including on a phone without an enrolled screen
lock. Hotel administration remains a separate authenticated screen.

Save and return selects direct Fish-hosted translation. Use saved key selects
the same route without replacing a readable stored key. An old hotel connection
is preserved but cannot override that explicit personal selection. Saving a
hotel connection from Staff setup selects managed translation again. Existing
session cleanup continues using its original endpoint and credential identity.

Keys retain the existing encrypted store and Keystore alias. This release does
not embed a shared provider key or require a backend operated by the user. A
genuinely absent key must be entered once; provider entitlement, billing, network
access and microphone permission still determine whether a live conversation
can start. The setup change does not establish an acoustic latency improvement.

Regression coverage includes both screen-lock states, cancel, encrypted save,
language preservation, activity recreation, saved-key reuse, direct-route
selection and the separate hotel-administration boundary. The emulator uses
synthetic credentials and never calls a voice provider. Signed publication also
requires the existing in-place upgrade and credential-retention checks.
