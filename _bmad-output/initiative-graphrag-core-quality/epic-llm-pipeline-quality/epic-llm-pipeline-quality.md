---
type: epic
title: "Better prompts, extraction and Global Search in graphrag-core"
parent: initiative-graphrag-core-quality
covers: [R1, R2, R3, R4, R5]
after: []
assignee: ""
risk: medium
---

# Better prompts, extraction and Global Search in graphrag-core

## Description

Improves every LLM-facing step of `graphrag-core` and its OpenAI adapter. Prompts are sharper and shared. Gleaning and name hints make extraction more complete and consistent. Long descriptions are summarised instead of cut off. Global Search becomes map-reduce over all Communities. Every new model call is opt-in or behind an existing capability flag, so ports without a model keep their deterministic results.

## Outcome

Library users and the demo get fewer duplicate entities, fuller descriptions and corpus-wide Global answers; the initiative's Done when checks are the signal.

## Requirements

- R1: `PromptedLlmPort` prompts state naming and relationship rules. Community summaries include relationship descriptions, and answers never cite background items and give partial answers. The comparison verdict is written by the model, and extraction can glean (opt-in).
- R2: One set of prompts: `OpenAiLlmPort` builds on `PromptedLlmPort` and keeps its OpenAI behaviour (JSON mode, no network retries, `LlmCallFailedException`, output-token-limit detection).
- R3: Extraction is told the entity names already found earlier in the same run, so it reuses them.
- R4: A merged description that outgrows its limit is summarised by the model (opt-in) instead of dropping later sightings; without a model nothing changes.
- R5: Global Search is map-reduce. Every Community is asked for rated key points, and the best points across Communities are reduced into one cited answer. The trace shows both steps, and the deterministic path stays without a synthesizing port.

## Done when

1. `env -u OPENAI_API_KEY mvn -B verify -Dapi.version=1.44` is green for every module.
2. Scripted `PromptedLlmPort` tests show gleaning, the verdict, name hints, description summaries and map-reduce each make exactly the expected model calls.
3. On the demo corpus with a real key, Global Search for "What are the main themes?" reads every Community and cites passages from more than one.
4. `OpenAiLlmPort` holds no prompt text that `PromptedLlmPort` also holds, and its tests pass.
5. `graphrag-core/CHANGELOG.md` and the READMEs describe every change, including each break for record patterns.

## Boundaries

`graphrag-core` and `graphrag-adapter-langchain4j`, plus the wiring and Global trace display in `graphrag-web`. Not the community detectors, the Neo4j adapter or new frontend views.

## References

- parent — _bmad-output/initiative-graphrag-core-quality/initiative-graphrag-core-quality.md, section Requirements
- constraint — graphrag-core/README.md, framework-free core
- constraint — graphrag-core/CHANGELOG.md, Keep a Changelog; additions go under [Unreleased] for 2.1.0
- reference — Microsoft GraphRAG global search (map-reduce over community reports with rated key points) and extraction gleaning, as the model for R1 and R5

## Notes

- Decision: entry 1 records the prompt, gleaning and verdict work already done in the worktree on 2026-10-06; it is closed by committing it (2026-10-08).
- Decision: entries run as one lane in this order, because 2–5 all change `PromptedLlmPort` (2026-10-08).
- Decision: entry 1 is the tracer bullet; it touches the core prompts, the OpenAI adapter and the docs (2026-10-08).
- Assumption: with a synthesizing port, map-reduce replaces the semantic top-3 path. When an EmbeddingPort is present, it orders the Communities by similarity and caps how many are mapped (configurable, default 30). Batch size is 5, points rated 0–100, points rated 0 dropped, and the best 20 points go to the reduce step (entry 5).
- Assumption: name hints are capped at the 50 most-mentioned resolved names, most-mentioned first (entry 3).
- Assumption: a description summary replaces the merged text completely, applies to Entities and Relationships, and is capped at the same 1000 characters (entry 4).
- Assumption: in entry 2, complete() keeps throwing LlmCallFailedException on a reply cut off by the output-token limit, so a truncated reply still fails visibly instead of being repaired.
- Assumption: the user supplies an OpenAI key and runs the demo check in entry 6.
- Assumption: map-reduce Global Search shows its map step as one `COMMUNITY` trace step per Community read and its reduce step as the existing synthesis, so the replay needs no new step kind.
- Assumption: description summaries run once per element at the end of an extraction run, not on every merge.
