/**
 * Port interfaces: the SPI (service provider interface) a consuming
 * application implements to plug in its own document parsing, LLM, graph
 * storage, embedding, and vector storage technology. Use cases in {@link
 * io.graphrag.core.usecase} depend only on these interfaces, never on a
 * concrete implementation, so {@code graphrag-core} itself stays free of any
 * web framework, graph-database driver, or LLM-orchestration library (AD-1).
 */
package io.graphrag.core.port;
