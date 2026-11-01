# Decision Models Test

A simple Kotlin CLI app for benchmarking decision AI models (OpenAI Decisions,
Jev via Opper.ai) against a standard LLM (e.g. the cheapest GPT) on an
occupation-classification task: given a job posting, predict the ISCO-08
occupation (unit group) it concerns.

## Data & artifacts (local/, gitignored)

Everything runtime lives under `local/` (see `.gitignore`):

- `local/occupations.json` — ISCO-08 occupations grouped major → sub_major →
  minor → unit (436 unit groups), produced by `prepare`.
- `local/ads/$unit-$lang-$title.md` — generated job postings (en/no/pl).
- `local/compare/results-<ts>.jsonl` — one JSON line per query (`QueryResult`:
  kind, stage, approach, model, durationMs, tokens, predictions, probabilities,
  correct, error).
- `local/secrets/OPENAI_API_KEY.txt`, `local/secrets/OPPER_API_KEY.txt` — API
  keys, one per file, never committed (covered by `.gitignore` + `.aiignore`)
  and never printed. The app reads them at runtime only.

## Steps

### Step 0 — Data preparation (`prepare`)

Download the ISCO-08 occupation list from ILO:
https://www.ilo.org/ilostat-files/ISCO/newdocs-08-2021/ISCO-08/ISCO-08%20EN.csv

Transform it into `local/occupations.json` as `{"majors":[...]}` grouped by
major → sub_major → minor → unit (436 unit groups expected).

### Step 1 — Job posting generation (`generate`)

For each of the 436 occupations, generate a sample job posting in Markdown via
a locally installed Bielik model served through Ollama. Language chosen
randomly: English, Norwegian, or Polish. Saved as
`local/ads/$unit-$lang-$title.md`.

### Step 2 — Result comparison (`compare`)

For randomly selected postings, query each benchmark **kind** and record
predictions + latency + probabilities to `local/compare/results-<ts>.jsonl`.

Benchmark kinds (each kind is a separate row set in the results, not a runtime
switch):

- `llm` — standard chat-completions LLM (`POST /v1/chat/completions`), flat list
  of all units in the prompt, JSON answer `{"units": [...], "reasoning": ...}`.
- `decisions-choice` — OpenAI Decisions API (`POST /v1/decisions`, model
  `gpt-6-luna`) with a single `choice` question. Per the API reference, a
  `choice` question takes **2–255 choices**.
- `decisions-score` — Decisions API with `score` questions; candidates are
  batched into questions of at most `SCORE_MAX_LEVELS = 10` levels
  (`SCORE_MIN_LEVELS = 2`). Per the API reference
  (https://developers.openai.com/api/reference/resources/decisions/methods/create),
  a `score` question takes **2–10 ordered levels** (`minItems 2 maxItems 10`) —
  so yes, the 10-option limit for `score` is real. A request may contain 1–400
  questions.
- `jev` — Jev via the Opper.ai gateway, model `typesafe/jev-1.14.0`, key from
  `local/secrets/OPPER_API_KEY.txt`. Opper exposes the same Decisions-shaped
  API at `POST https://api.opper.ai/v3/compat/decisions` (auth:
  `Authorization: Bearer <OPPER_API_KEY>`), so the same client code works by
  switching base URL, key and model. Native System One endpoint:
  `POST https://api.opper.ai/v3/compat/v1/systemone`. See
  https://docs.opper.ai/build/gateway/evaluations — question types map 1:1
  (`predicate`→`noul`, `choice` 2–255 options, `score` 2–10 levels; levels are
  0-indexed and the score can be fractional). Note: Jev has a 64k total context
  limit (32k for state + longest question).

Decisions/Jev kinds use hierarchical prompting: first match at the `minor`
group level, then the `unit` level restricted to units of the predicted
minors. Predictions are the top-N (N=3) candidates with probability ≥ 0.3.

## Running

```
./kotlin run -- prepare
./kotlin run -- generate
./kotlin run -- compare --ads 10 --seed 42 --delay-ms 1000
./kotlin test
```

`compare` configuration (env vars, all optional):

- `OPENAI_BASE_URL` (default `https://api.openai.com`)
- `LLM_MODEL` (default `gpt-6.1-sol`)
- `DECISIONS_MODEL` (default `gpt-6-luna`)
- `DECISIONS_APPROACHES` (default `predicate,choice,score`) — legacy switch;
  the plan is to replace it with explicit per-kind runs (`decisions-choice`,
  `decisions-score`).
- `OPPER_BASE_URL` / `OPPER_MODEL` / `JEV_MODEL` — for the `jev` kind
  (defaults: `https://api.opper.ai/v3/compat`, `typesafe/jev-1.14.0`).

## Workflow

Each step is implemented one at a time: a minimal solution / tech stack is
proposed first, then approved or adjusted before implementation. Commit after
each completed step. Session notes and working state go to `local/`, not to
the repo.
