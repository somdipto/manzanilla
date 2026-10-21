# Bubble placement by spoken language — 17.0.26 candidate

Receptionist-language speech belongs on the right; guest-language speech belongs
on the left, using the two languages selected for this session. Speech with
uncertain language keeps the preceding source side. An uncertain first utterance
is centered because there is no preceding evidence. Only new language evidence
can change a side. This is language attribution, not voice identification.

## Reproduced defects

An isolated replay of the 17.0.25 renderer reproduced:

- German receptionist speech followed by three `204` utterances produced
  staff/guest/staff/guest. Its no-evidence fallback explicitly inverted the side.
- A German final transcript stayed on the guest side if output arrived before
  the final source revision. Translation arrival disabled reclassification.
- French and German source segments could merge into one bubble before output
  arrived. Output timing was being used as the utterance boundary.

The old browser fixture asserted alternation for unclear input. Its common-word
table coverage tests proved table membership, not correct attribution.

## Provider research and chosen contract

Checked the official Fish documentation and OpenAPI on 2026-10-21:

- [Wire protocol](https://docs.fish.audio/agents/deploy/protocol): raw
  `lk.transcription` streams expose segment identity, finality and user/agent
  roles. The documented contract does not expose a heard-language label or an
  original/translation relationship. It cannot identify two people on one mic.
- [Custom LLM](https://docs.fish.audio/agents/build/custom-llm): recognition is
  delivered as plain user text. Merely selecting a custom endpoint would not
  recover acoustic language metadata or a missing segment link.
- [Client tools](https://docs.fish.audio/agents/build/client-tools): a client
  tool can carry model-generated parameters separately from spoken content.
  `expects_response: false` lets speech proceed without a client acknowledgement.
- [OpenAPI](https://docs.fish.audio/api-reference/openapi.json): tool-library
  creation supports `tool_type: client`, named arguments, `expects_response` and
  `execution_mode: fire_and_forget`; agents attach tools by library ID.
- [Direct Scribe realtime](https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime)
  can return a delayed detected language after a commit. Fish's hosted stream
  does not document forwarding that information. A separate recognizer would
  add a second audio path and attribution problem.

Use the existing translation model's source-language decision through one
strictly display-only client tool, `manzanilla_publish_interpretation_v1`.
The report carries `source_language`, `original` and `translation`. Its Fish
`callId` identifies the entire caption; no matching by repeated text, participant,
turn parity or output arrival is needed. The model reports each utterance once,
including repeats and unclear input, before speaking the translation. Its fixed
opening greeting and its own output are excluded.

The label is explicitly `language_attribution: model`, not an ASR confidence
measurement. Speech/language recognition and model compliance still require
live acceptance; synthetic metadata cannot prove either one.

## Implementation

- Android validates the exact tool name, nonblocking flag, call identity,
  string types, size limits and selected-language membership. A complete known
  language requires a translation. `und` requires an empty translation. Other
  tool calls remain unsupported and terminate the interpreter as before.
- Valid reports become one atomic `interpretation` bridge event with original,
  translation, language and a stable ID. Duplicate IDs are ignored; identical
  words under new IDs remain separate utterances. Voice-session ownership adds
  session/generation scope and fences late producers after stopping.
- The renderer derives physical sides solely from source-language metadata.
  Locale variants such as `de-DE` normalize to the selected base code. Word
  tables, script guesses, repeat matching and opposite-side fallback are removed.
- Uncertain utterances inherit the immediately preceding side in source order.
  Late metadata can resolve dependent uncertain records, but cannot override a
  later record with its own language evidence. Restarts scope identity and
  continuity separately even when earlier history stays visible.
- Legacy raw-caption handling keeps separate source segments and neutral,
  unlinked output. Output joins a source only with an explicit reference.
  Text finality does not prevent later explicit language metadata.
- Raw LiveKit streams retain transport/timing and human-activity observations; the live renderer
  receives complete model reports. Captions describe the full interpretation,
  not a playback-synchronized transcript of words already heard. An interrupted
  spoken translation can therefore be shorter than its displayed text.
- Direct and managed provisioning attach only this caption tool. Existing
  definitions are verified and reused, never overwritten. Changed tool contracts
  fail setup before a paid session is created. Published agents stay immutable.
- Managed requests explicitly opt into `caption_protocol: language-v1`. Older
  installed clients keep an agent without client tools. Deploy this backend
  before the new managed app; an older backend rejects the new request safely.

## Latency and limitations

Recognition routing, Gemini/Fish models, WebRTC audio, echo cancellation and
endpointing from 17.0.25 are preserved. There is no second classification service
and no acknowledgement round trip before speech. However, generating a caption
tool call and then the spoken translation can add model work and first-audio
delay, particularly for long speech. Fire-and-forget does **not** establish zero
latency cost. `model_language_caption_received` is logged without guest text for
correlation with the existing input-final and decoded-output timing markers.

Missing or malformed model reports never trigger guessed placement. Malformed
reports show a caption-unavailable notice; an omitted report can mean missing
captions. This implementation cannot force a hosted model to issue a tool call
or make an acoustically ambiguous utterance certain. Model compliance, caption
coverage, bilingual quality and latency must be checked together before release.

## Regression and device acceptance

The executable language-ledger suite exercises all 2,652 ordered picker pairs,
same-language repeats, unclear numbers/names/shared words, both role directions,
late labels, finality, unsupported labels, explicit output references, duplicate
IDs and new-session continuity. The native report tests cover all pairs and the
bridge validation/ownership boundary. The browser fixture checks real DOM sides,
positions, labels, escaping, scrolling, microphone controls and stale sessions.
Backend fixtures verify opt-in compatibility and read/create/publish boundaries
without real credentials, provider changes or paid calls.

For live acceptance on an isolated Gama 804, use Spanish/German in both role
assignments, Chinese/Japanese, Arabic/French and a similar-script pair. For each:

1. Each party says a complete utterance three times, pauses and continues.
2. Insert names, room numbers and shared one-word replies after each side.
3. Speak a new language before the prior output finishes; interrupt and restart.
4. Compare recordings to every model report and bubble. Count missing reports,
   wrong language labels and unsupported side changes separately.
5. Measure end-of-human-speech to first audible translation p50/p95 against
   17.0.25, including long sentences. Check that tool metadata is never spoken.

No physical-device acoustic, live provider or paid-session result is claimed.
See [verification](verification/README.md) for completed checks.
