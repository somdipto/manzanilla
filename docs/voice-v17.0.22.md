# Voice configuration update: 17.0.22

Conversation-first update. The direct Fish route remains in this version; no backend or pairing is required for an existing standalone install.

## Changes

- Live-translation bubbles follow chat convention: the guest's speech sits on the left, the receptionist's on the right, and each translation renders as a plain line at the bottom of the speaker's own bubble. There is no "AI" tag, provider label, or "Assistant output" text anywhere in translation mode.
- Speaker labels come only from the turn layout, not from guesses. With one shared microphone the device cannot know who spoke, so unlabeled human turns alternate sides after each translated turn (first turn: guest). The Unspecified/Guest/Receptionist pills and the native forced-speaker path are removed: speaker attribution is never hardcoded or manually pinned.
- One microphone button replaces the meter orb and speaker pills: green when idle, red while capturing, tap to mute, tap again to unmute. Muting disables the LiveKit microphone track without releasing capture ownership or ending the session; the privacy line reads "Mic muted" honestly. A mute toggle no longer rebuilds the transcript, so streaming captions never flicker mid-utterance.
- The interpreter prompt now requires per-utterance language identification: each new utterance is classified as guest language or hotel language from its own words before translation, never guessed from the previous utterance. This targets the failure where one person's run of speech was translated back into their own language. Fish configuration revision moves 9 to 10 so existing installs pick up the new prompt.
- Translation turns merge provider segments into one bubble per turn of speech; assistant output attaches to the most recent human turn by arrival order because provider segment IDs never link an utterance to its translation.

## Security limit

The standalone public APK still contains the provider credential. It is extractable. This is a temporary known risk, not a secure production design. Do not rotate it after this update: this version still needs that credential. Rotation belongs after a keyless backend release is deployed and devices have migrated.

## Acceptance on the tablet

Update to 17.0.22, end any existing conversation, then start a new one. Check: bubbles alternate sides plausibly during a two-person exchange; translations appear at the bottom of the speaker's bubble with no AI labeling; a run of speech in one language stays one bubble and is answered in the other language even when the previous utterance was in the same language (speak two guest sentences in a row); the mic button turns red while capturing and mutes/unmutes on tap; muted speech produces no captions. Local tests cannot prove actual speech quality, hotel acoustics, provider behavior, or real-device latency. No physical-device acceptance is claimed.

## Known limits

- A mute tap during the first seconds of connection, before the audio room exists, is dropped without feedback; tap again once the session is live.
- Per-utterance language identification is prompt-level. The provider's `voice.speaking_language` still sets one output language per session; the prompt's override is verified only by real speech on the tablet.
- Turn sides for unlabeled speech alternate by order, not by who actually spoke. A turn misattributed by the provider still lands on the wrong side.

## Local validation and review

- Full Python suite: 241 passed (Python 3.12; 3.10 lacks asyncio.timeout used by the cloud route).
- Browser fixture (tests/reception_caption_fixture.js) run in a real Chromium against the shipped assets: all assertions passed, covering turn merge, side alternation, greeting placement, HTML escaping, finality, absence of AI labels and speaker pills, mic mute toggle, mic-level-only updates, scrolling, full re-render relinking, and stale-session reset (601 caption updates replayed in about 274 ms).
- Android unit suite and debug APK build: pending toolchain in the development environment (JDK 17 and Android SDK unavailable); see the ship notes for the CI build path.
- No mock screenshots were produced as device proof; the tablet owner runs acceptance via the APK.
