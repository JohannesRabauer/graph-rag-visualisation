---
title: GraphRAG Visualization Tool — Product Brief
status: draft
created: 2026-09-19
updated: 2026-09-19
---

# Product Brief: GraphRAG Lens *(working title — needs a real name)*

## Executive Summary

GraphRAG Lens is a Java web application that makes GraphRAG visible. It takes a small public-domain fiction corpus (the Sherlock Holmes stories), builds a knowledge graph of it in Neo4j through live LLM calls, clusters that graph into communities, and lets someone type a real question and watch how the answer actually gets assembled — via entity-level local search or community-level global search. Nothing is scripted or cached: every run is a genuine LLM call against a genuine graph.

It exists because GraphRAG is one of the most talked-about evolutions of RAG right now, but almost impossible to actually *see* working, and because existing tooling barely touches Java. This is, first, a passion project for its creator — a way to understand GraphRAG deeply enough to explain it live, correctly, on a coding stream — built with enough care that it could later become the seed of a Java-native GraphRAG library, in a space that turns out to have confirmed whitespace.

## The Problem

GraphRAG is described constantly in blog posts and papers — entity graphs, community summarization, local vs. global search — but almost never *shown* running. The tools that come closest fall short in specific ways:

- Neo4j's own official GraphRAG SDK (`neo4j-graphrag-python`) and LLM Knowledge Graph Builder are Python-only; there is no first-party Java equivalent, and Neo4j's own blog frames deeper GraphRAG support in LangChain4j as future work.
- The closest existing visualizer (`graphrag-visualizer`) is a post-hoc viewer of Microsoft GraphRAG's exported parquet files — not a live, interactive demo.
- Strong "visual RAG explainer" demos exist (RAG Playground, RAGViz) but they all explain plain vector RAG — none touch graph traversal or community structure.

The practical cost: a Java developer curious about GraphRAG has to either learn it through Python tooling that doesn't match their stack, or take the mechanics on faith from articles and diagrams.

## The Solution

A locally run Java application with a web UI that:

1. Ingests the Sherlock Holmes corpus and extracts entities/relationships into a Neo4j graph via live LLM calls.
2. Runs community detection (Leiden-style clustering) on that graph and visualizes the clustering itself as a distinct, watchable step — not just its output.
3. Accepts a real user query and answers it two ways: **local search** (entity-neighborhood traversal, for specific questions) and **global search** (community-summary map-reduce, for corpus-wide "what are the themes" questions) — visualizing which nodes and communities each path actually touches.

Built in Java with LangChain4j as the LLM orchestration layer, OpenAI as the initial model provider (deliberately chosen to be swappable), and Neo4j as the graph store. The UI is modern, minimalist, and runs entirely in the browser with no heavy client install.

## What Makes This Different

- **Nobody visualizes GraphRAG's live retrieval mechanics.** Existing prior art is either post-hoc (parquet viewers) or scoped to plain vector RAG. Watching a query actually traverse a graph and pull from specific communities, in real time, appears to be genuinely unclaimed territory.
- **It's Java-native in a Python-dominated space.** A GitHub search on GraphRAG-related topics turns up almost nothing Java; Neo4j's own tooling doesn't reach Java developers at all today.
- **Honest framing of the moat**: this is not a technical moat, it's a timing-and-effort one — being an early, real, working example in an underserved niche. No fabricated defensibility beyond that.

## Who This Serves

- **Primary: the creator.** The project's first job is building real understanding — deep enough to explain GraphRAG confidently and correctly, live, using the running app as the teaching aid.
- **Secondary: live coding stream viewers** — developers curious about GraphRAG or about the state of Java's RAG ecosystem, watching a real system get built and explained rather than reading about one.
- **Tertiary, explicitly future**: Java developers who'd use this as a starting point for their own GraphRAG-on-Neo4j work, if it's later extracted into a library. Not a v1 concern, but a reason architecture decisions are made carefully now.

## Success Criteria

- **Primary, and sufficient on its own**: the creator can explain GraphRAG confidently and correctly, live, using the app.
- **Secondary**: the demo is genuinely engaging to watch on stream — while accepting that a live LLM call failing mid-demo is a real possibility the project deliberately does not engineer around (see Scope).
- **Aspirational, not a v1 gate**: the codebase stays clean enough that extracting a reusable Java GraphRAG library later is realistic, not a rewrite.

## Scope

**In for v1:**
- Fixed corpus: public-domain Sherlock Holmes stories
- Neo4j as the only graph store
- Live LLM-driven knowledge graph construction (entity/relationship extraction) — real calls, non-deterministic by design
- Community detection/clustering, visualized as its own step
- Local search and global search, both visualized and queryable
- OpenAI via LangChain4j as the LLM integration, chosen specifically so the provider is easy to swap later
- Single local user, run on a developer machine — no auth, no hosting, no multi-tenancy
- Modern, minimalist, browser-based UI with minimal setup friction

**Explicitly out for v1:**
- Arbitrary or user-uploaded corpora (fixed corpus only, for now)
- Graph databases other than Neo4j
- Formal retrieval-quality benchmarking or evaluation dashboards
- Any fallback/cached "safety net" for live-demo LLM failures — failure on stream is an accepted risk, not a defect
- Packaging or publishing this as a standalone library — a deliberate future step, not a v1 deliverable

## Vision

Best case, this stops being just a personal demo. Because it's architected cleanly from day one rather than hacked together, it becomes the starting point for a small but real Java-native GraphRAG library on Neo4j — filling a gap the research behind this brief confirmed is real, not assumed. And the visualization approach itself — watching a graph fold into communities, watching a query actually traverse it — becomes a reference for *how to explain* GraphRAG, not just a one-off internal tool.
