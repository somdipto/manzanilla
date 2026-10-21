# Voice configuration update: 17.0.24

Speaker-side fix. Translation bubbles now follow the two selected languages instead of blind alternation.

## Changes

- Each human turn lands on the side of the selected language its text matches: the receptionist's picker language on the right, the guest's on the left, for any of the 52 picker languages, in both directions. Nothing is pinned to English or any fixed language; the sides always derive from the two languages chosen in the pickers.
- Evidence order for every turn: a repeat or near-repeat of a recent turn keeps that turn's side; a distinct script (Cyrillic vs Latin, Arabic vs Devanagari, and so on) decides exactly; same-script pairs are separated by small common-word tables built for all 40 languages that can collide; with no evidence at all (numbers, names), the side alternates from the previous turn.
- A receptionist or guest who repeats a phrase several times no longer flips sides - the reported bug where turns two and three of a repeated phrase jumped to the other side is fixed by construction.
- A speaker who pauses and then continues keeps their side: mid-turn pauses stay in one bubble, and a returning speaker's new words in their own language match their side again.
- Sides recompute while a turn's text is still streaming, so an ambiguous first word corrects itself as evidence arrives; both the bubble position and its label update together.

## Why client-side classification

The provider wire carries no language or speaker identity for human turns (checked against the Fish protocol documentation: segments carry only an id and a final flag, and roles distinguish only user versus agent). Side attribution therefore comes from classifying the transcript text on the device against the two selected languages.

## Known limits

- Very short utterances whose words exist in both languages ("no", "OK") can tie and fall back to alternation.
- Indonesian and Malay share most common words; a short sentence in one can score for the other.
- Japanese text written with no kana (rare in speech transcripts) looks like Han script and follows the Chinese side of a Chinese/Japanese pair.

## Acceptance on the tablet

Update to 17.0.24 and start a new conversation. Check: a repeated phrase two or three times from the same speaker stays on one side; each speaker's turns stay on their side across pauses; a short exchange in a same-script pair (Spanish/English) and a distinct-script pair (Russian/Spanish) lands on the correct sides in both directions. Local tests cannot prove real speech classification, hotel acoustics, or provider transcription quality; no physical-device acceptance is claimed.

## Local validation and review

- Real-Chromium fixture: all assertions pass (636 caption updates, about 300 ms replay), covering the repeat-three-times case, pause-then-resume continuity, same-script and distinct-script pairs in both directions, a seven-pair matrix (fr/de, ru/uk, ar/fa, hi/mr, zh/ja, ko/es, th/de), no-evidence alternation, and streaming reclassification of position and label. All pre-existing fixture behavior still passes.
- Full Python suite: 243 passed, including a new static test proving every one of the 52 picker languages is covered by the script and common-word tables.
- Android unit suite: 84 passed.
- Review found and fixed one defect before release: reclassified turns originally kept their initial bubble position because the side class was only set at creation.
- No mock screenshots were produced as device proof; the tablet owner runs acceptance via the APK.
