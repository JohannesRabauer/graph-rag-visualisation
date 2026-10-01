/**
 * Use cases: the actual operations a consumer calls, orchestrating the
 * domain model against the ports it has wired in. Called in pipeline order —
 * ingest ({@link dev.rabauer.graphrag.core.usecase.IngestCorpus}), extract entities
 * and relationships and build the graph ({@link
 * dev.rabauer.graphrag.core.usecase.ExtractEntitiesAndRelationships}), then detect
 * communities ({@link dev.rabauer.graphrag.core.usecase.DetectCommunities}) and build
 * the vector index ({@link dev.rabauer.graphrag.core.usecase.ConstructVectorIndex}),
 * and finally answer questions (e.g. {@link
 * dev.rabauer.graphrag.core.usecase.AnswerLocalSearch}) — as documented in this
 * module's README.
 */
package dev.rabauer.graphrag.core.usecase;
