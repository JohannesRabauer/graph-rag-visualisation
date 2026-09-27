/**
 * Use cases: the actual operations a consumer calls, orchestrating the
 * domain model against the ports it has wired in. Called in pipeline order —
 * ingest ({@link io.graphrag.core.usecase.IngestCorpus}), extract entities
 * and relationships and build the graph ({@link
 * io.graphrag.core.usecase.ExtractEntitiesAndRelationships}), then detect
 * communities ({@link io.graphrag.core.usecase.DetectCommunities}) and build
 * the vector index ({@link io.graphrag.core.usecase.ConstructVectorIndex}),
 * and finally answer questions (e.g. {@link
 * io.graphrag.core.usecase.AnswerLocalSearch}) — as documented in this
 * module's README.
 */
package io.graphrag.core.usecase;
