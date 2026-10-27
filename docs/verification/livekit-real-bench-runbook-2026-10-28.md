# Real-provider bench runbook (prepared 2026-10-28, NOT yet executed)

Scope: first real-provider measurement of the self-hosted route (issue #8).
Runs scripts/livekit_pipeline_bench.py: real Fish realtime ASR, real LLM, real
Fish streaming TTS; only the device's input speech is synthesized (no human speaker).
User approval on file (01:11 IST voice note): "You can use any type of credits,
whatever access I have given." Bounded by main to EXISTING credits, no card.

## Preconditions (checked 2026-10-28 ~01:13 IST)

- [ ] vault write-file healthy (DEGRADED at prep time: path returned, file absent;
      blocks FISH_API_KEY, FISH_VOICE_ID source and LLM key). Re-test first.
- [ ] Fish balance + charge behavior read BEFORE any spend:
      GET https://api.fish.audio/v1/wallet/{user_id}/api-credit (read-only, $0).
      Confirm existing-credit coverage; do not proceed if it would bill a card.
- [ ] livekit-server: no docker in the bench sandbox; use the standalone binary
      (github.com/livekit/livekit/releases), pass via --livekit-bin.
- [ ] python venv with voice-worker/requirements.txt.
- [ ] LLM: saved Gemini key via OpenAI-compatible route (production model per
      docs/selfhosted-voice-route.md). HELD until the key's tier is verified as
      free quota or existing credit - the user's grant covers existing credits
      only, and a paid-tier Gemini leg (est. ~$0.05 per 10-min bench at Gemini 3
      Flash $1/M in, $5/M out through Nov 30, ai.google.dev pricing) is NOT
      approved card spend. If tier cannot be verified, hold the whole bench.
      Fish share at list prices: ASR $0.36/session-hour + TTS s2.1-pro
      $15/M UTF-8 bytes; ~10 min ~ $0.27, drawn from existing Fish credit only,
      confirmed by the pre-run balance read above.

## Run

  livekit-server --dev --bind 0.0.0.0 &   # disposable local server
  FISH_API_KEY=... FISH_VOICE_ID=... LLM_API_KEY=... \
  python scripts/livekit_pipeline_bench.py \
      --llm-base <gemini-openai-compatible-url> --llm-model <model> \
      --livekit-bin <path> --out /tmp/bench-out

## Evidence to preserve

- Bench output dir (per-stage timings, interruption events) under docs/verification/
  with the exact env model names, key suffixes redacted, and cost actually drawn
  (post-run balance read for the delta).
- Labels: REAL providers, scripted device, no human speaker, no acoustic capture.
  Not guest-audible latency on the physical tablet.

## Abort / cleanup

- Kill worker + server; delete any key files written via vault write-file.
- Report: measured numbers, cost delta, and whether results justify proposing
  deployment (deployment itself is a separate costed decision needing his go).
