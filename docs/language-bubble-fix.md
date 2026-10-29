# Bubble placement by spoken language

Receptionist-language speech belongs on the right; guest-language speech belongs
on the left, using the two languages selected for this session. Speech with
uncertain language is centered after either speaker. Explicit later language
evidence can change only its own legacy caption. This is language attribution,
not voice identification. The 17.0.38 repair removes earlier side inheritance.

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
- [Webhook tools](https://docs.fish.audio/agents/build/webhook-tools): current
  documentation scopes `execution_mode` to webhooks. Client caption definitions
  omit it; readback defaults such as `blocking` do not change `expects_response:false`.
- [Direct Scribe realtime](https://elevenlabs.io/docs/api-reference/speech-to-text/v-1-speech-to-text-realtime)
  can return a delayed detected language after a commit. Fish's hosted stream
  does not document forwarding that information. A separate recognizer would
  add a second audio path and attribution problem.

Use the existing translation model's source-language decision through one
strictly display-only client tool. New clients use
`manzanilla_publish_interpretation_v2`; legacy v1 remains separately provisioned.
The report carries `source_language`, `original` and `translation`. Its Fish
`callId` identifies the entire caption, without matching by repeated text,
participant, parity or output arrival. V2 speaks a clear-language translation
before reporting; v1 reports before speaking. Both report `und` silently with an
empty translation and exclude the greeting and the interpreter's own output.

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
- Uncertain utterances remain neutral. Late language metadata changes its own
  legacy caption only, never an uncertain successor. Session/generation identity
  fences restarts even when earlier history stays visible.
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
- Managed requests explicitly select `caption_protocol: language-v2` or legacy
  `language-v1`. Older clients without captions retain their no-tool contract.
  Deploy the compatible backend before the managed app.

## Latency and limitations

Recognition routing, Gemini/Fish models, WebRTC audio, echo cancellation and
endpointing from 17.0.25 are preserved. There is no second classification service
and no acknowledgement round trip before speech. Caption generation and hosted
scheduling can still affect audible output; v2's speech-before-report instruction
does not establish zero latency cost. `model_language_caption_received` is logged
without guest text for
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
