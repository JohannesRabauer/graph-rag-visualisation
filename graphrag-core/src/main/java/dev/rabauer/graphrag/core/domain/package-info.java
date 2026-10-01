/**
 * Domain model: the plain data types carried between use cases and ports.
 * These types carry no framework or storage-technology dependency (AD-1);
 * they are what a port implementation reads and writes and what a use case
 * operates on.
 *
 * <ul>
 *   <li>{@link dev.rabauer.graphrag.core.domain.Corpus} and {@link
 *       dev.rabauer.graphrag.core.domain.UploadedDocument} — the ingested corpus and
 *       its source documents.</li>
 *   <li>{@link dev.rabauer.graphrag.core.domain.Chunk} and {@link
 *       dev.rabauer.graphrag.core.domain.EmbeddedChunk} — chunked document text and
 *       its embedding/projection.</li>
 *   <li>{@link dev.rabauer.graphrag.core.domain.Entity}, {@link
 *       dev.rabauer.graphrag.core.domain.Relationship}, and {@link
 *       dev.rabauer.graphrag.core.domain.GraphExtraction} — the extracted knowledge
 *       graph.</li>
 *   <li>{@link dev.rabauer.graphrag.core.domain.Community} and {@link
 *       dev.rabauer.graphrag.core.domain.CommunityMembership} — detected community
 *       structure over the graph.</li>
 *   <li>{@link dev.rabauer.graphrag.core.domain.ProjectionModel} — the fitted 2D
 *       projection used to place chunk and query embeddings.</li>
 *   <li>{@link dev.rabauer.graphrag.core.domain.RetrievalTrace} and {@link
 *       dev.rabauer.graphrag.core.domain.RetrievalStep} — a captured record of how an
 *       answer was retrieved.</li>
 *   <li>{@link dev.rabauer.graphrag.core.domain.UnsupportedFileTypeException} and
 *       {@link dev.rabauer.graphrag.core.domain.UnreadableDocumentException} — the
 *       failure modes surfaced during ingestion.</li>
 * </ul>
 */
package dev.rabauer.graphrag.core.domain;
