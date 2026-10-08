---
type: initiative
title: "graphrag-core produces better graphs and answers"
parent: none
covers: [R1, R2, R3, R4, R5]
assignee: ""
risk: medium
---

# graphrag-core produces better graphs and answers

## Description

The LLM-facing parts of `graphrag-core` catch up with what GraphRAG is known for. Extraction gives consistent names and finds more of what a passage says. Merged descriptions keep what later passages add. Global Search reads the whole corpus instead of three communities. One set of prompts serves both the library (`PromptedLlmPort`) and the web app (`OpenAiLlmPort`). The work fits one epic: one owner, one library plus its LangChain4j adapter. The initiative exists only because tickets are drafted under an initiative folder.

## Outcome

Library users and the GraphRAG Lens demo get fewer duplicate entities, fuller descriptions, and Global answers that draw on more than one community. The signals are the scripted tests in Done when and the demo corpus run.

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

`graphrag-core` (domain, ports, use cases, `llm` package) and `graphrag-adapter-langchain4j`. Not the community detectors, not the Neo4j adapter, not the frontend beyond showing the trace steps it already knows. Tracer path: the prompt work already in the worktree, committed and released as part of 2.1.0.

- Touch point: graphrag-web — wiring of the LLM port options and the Global trace in the replay; owner: epic-llm-pipeline-quality

## References

- source — this session's analysis of `graphrag-core` (2026-10-06), recorded in Requirements above
- constraint — `graphrag-core/README.md`, framework-free core (Maven Enforcer bans Spring, Neo4j driver, LangChain4j)
- constraint — `graphrag-core/CHANGELOG.md`, Keep a Changelog and SemVer; 2.1.0 is the next minor

## Notes

- Decision: one epic holds all five items; one owner and one library (user's request "log everything as issues through bmad, then start implementing", 2026-10-08).
