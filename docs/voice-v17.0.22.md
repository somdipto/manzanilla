# Voice configuration update: 17.0.22

Historical conversation-first configuration record, not current behavior or installation guidance. This version used direct Fish without enrollment. Its alternating-side and arrival-order caption rules were later removed: current repair turns use explicit language evidence and unclear language stays neutral. Follow [the current artifact and qualification contract](../README.md).

## Changes recorded at the time (superseded caption rules)

- Live-translation bubbles follow chat convention: the guest's speech sits on the left, the receptionist's on the right, and each translation renders as a plain line at the bottom of the speaker's own bubble. There is no "AI" tag, provider label, or "Assistant output" text anywhere in translation mode.
- Speaker labels come only from the turn layout, not from guesses. With one shared microphone the device cannot know who spoke, so unlabeled human turns alternate sides after each translated turn (first turn: guest). The Unspecified/Guest/Receptionist pills and the native forced-speaker path are removed: speaker attribution is never hardcoded or manually pinned.
- One microphone button replaces the meter orb and speaker pills: green when idle, red while capturing, tap to mute, tap again to unmute. Muting disables the LiveKit microphone track without releasing capture ownership or ending the session; the privacy line reads "Mic muted" honestly. A mute toggle no longer rebuilds the transcript, so streaming captions never flicker mid-utterance.
- The interpreter prompt now requires per-utterance language identification: each new utterance is classified as guest language or hotel language from its own words before translation, never guessed from the previous utterance. This targets the failure where one person's run of speech was translated back into their own language. Fish configuration revision moves 9 to 10 so existing installs pick up the new prompt.
- Translation turns merge provider segments into one bubble per turn of speech; assistant output attaches to the most recent human turn by arrival order because provider segment IDs never link an utterance to its translation.

## Security limit

This historical standalone APK contained an extractable provider credential,
not secure production credential storage. The former instruction to defer
rotation until a keyless deployment is withdrawn. The known public 17.0.37
personal-testing APK also exposes a disposable Fish key; its account owner
must rotate/revoke it after testing. A key-free hotel artifact cannot revoke
previous copies. See [current credential guidance](ENVIRONMENT.md#signing-and-publication).

## Qualification boundary

The former instruction to install 17.0.22 and accept plausible side alternation
is withdrawn. Do not qualify current caption ownership by turn order. Use the
exact current artifact and [the acoustic protocol](TEST-PLAN.md#voice-repair-reproduction-unreleased):
held-out inputs, repetitions, both directions, common-clock captures and human
meaning/naturalness review. Local caption/mute checks are not physical audio,
provider entitlement or bilingual accuracy proof. Current external hardware,
budget and staff gates remain blocked; see [pilot prerequisites](PILOT-DEPLOYMENT.md).

## Historical limits of 17.0.22

- During that version's initial connection, mute taps before room creation were dropped without feedback. This historical limitation is not an instruction to accept that behavior now.
- Per-utterance language identification was prompt-level. The configured output language and prompt alone did not establish correct live bilingual speech on the tablet.
- Turn sides for unlabeled speech alternate by order, not by who actually spoke. A turn misattributed by the provider still lands on the wrong side.

## Historical local validation, not current qualification

- Full Python suite: 241 passed (Python 3.12; 3.10 lacks asyncio.timeout used by the cloud route).
- Browser fixture (tests/reception_caption_fixture.js) run in a real Chromium against the shipped assets: all assertions passed, covering turn merge, side alternation, greeting placement, HTML escaping, finality, absence of AI labels and speaker pills, mic mute toggle, mic-level-only updates, scrolling, full re-render relinking, and stale-session reset (601 caption updates replayed in about 274 ms).
- Android unit suite and debug APK build: pending toolchain in the development environment (JDK 17 and Android SDK unavailable); see the ship notes for the CI build path.
- No mock screenshots were produced as device proof, and no physical acceptance result was recorded by this note.
