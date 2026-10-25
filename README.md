# Decision Models Test

A simple Kotlin application for testing decision AI models (e.g. Jev) against a
standard LLM (e.g. the cheapest GPT) on an occupation-classification task.

## Step 0 — Data preparation

Download the ISCO-08 occupation list from ILO:
https://www.ilo.org/ilostat-files/ISCO/newdocs-08-2021/ISCO-08/ISCO-08%20EN.csv

Transform it into an easy-to-parse JSON file stored at `local/occupations.json`,
containing the ISCO-08 occupations filtered and grouped hierarchically by the
columns: `major` → `sub_major` → `minor` → `unit` (436 unit groups expected).

## Step 1 — Job posting generation

For each of the 436 occupations, use a locally installed Bielik model (served
via Ollama) to generate a sample job posting in Markdown. The language is
chosen randomly: English, Norwegian, or Polish. Postings are saved to
`local/` with filenames of the form `$unit-$lang-$title.md`, e.g.

- `local/9212-en-cowboy_needed_at_ranch.md`
- `local/2132-pl-programiści_java_poszukiwani.md`

The prompt for generation needs to be designed.

## Step 2 — Result comparison

For randomly selected postings, send queries to:

1. a standard LLM (e.g. the cheapest GPT) via the OpenAI or Opper.ai API
   (API key to be provided later), and
2. a decision model (e.g. Jev) via the same API provider.

The prompt is in the style: "here is a list of occupations and here is a job
posting — tell me which occupation it concerns (max three entries)".

Results (predictions + query times) are saved to a report for comparison.

## Workflow

Each step is implemented one at a time: a minimal solution / tech stack is
proposed first, then approved or adjusted before implementation.
