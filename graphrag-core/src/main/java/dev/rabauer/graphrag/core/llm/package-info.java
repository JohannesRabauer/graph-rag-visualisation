/**
 * Framework-free helpers for talking to language models, shared by every LLM
 * adapter — including third-party ones built on other frameworks — so they all
 * interpret model replies the same way. {@link dev.rabauer.graphrag.core.llm.LenientJson}
 * pulls the JSON out of a small model's reply even when it is wrapped in prose or
 * markdown fences, carries trailing commas, or was cut off at the token limit.
 */
package dev.rabauer.graphrag.core.llm;
