package dev.rabauer.graphrag.web;

import dev.rabauer.graphrag.adapter.neo4j.Neo4jCorpusRegistry;
import dev.rabauer.graphrag.core.domain.Chunk;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.EmbeddedChunk;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.domain.Sources;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.port.VectorStorePort;
import dev.rabauer.graphrag.core.usecase.EmbedGraphElements;
import dev.rabauer.graphrag.core.usecase.SourceRemoval;
import dev.rabauer.graphrag.core.usecase.UpdateSources;
import dev.rabauer.graphrag.web.CorpusEventPayloads.SourceLabel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static dev.rabauer.graphrag.web.CorpusEventPayloads.communityEventPayload;
import static dev.rabauer.graphrag.web.CorpusEventPayloads.entityEventPayload;

/**
 * Reads of stored corpora: the history list, activation, the whole graph,
 * single passages and chunks, and the vector-space positions. Nothing here
 * calls an LLM or an embedding model.
 */
@RestController
public class CorpusGraphController extends CorpusApiController {

    private final Neo4jCorpusRegistry corpusStore;
    private final GraphStorePort graphStorePort;
    private final VectorStorePort vectorStorePort;
    private final LlmPort llmPort;
    private final EmbeddingPort embeddingPort;

    public CorpusGraphController(Neo4jCorpusRegistry corpusStore, GraphStorePort graphStorePort,
                                 VectorStorePort vectorStorePort, LlmPort llmPort, EmbeddingPort embeddingPort) {
        this.corpusStore = corpusStore;
        this.graphStorePort = graphStorePort;
        this.vectorStorePort = vectorStorePort;
        this.llmPort = llmPort;
        this.embeddingPort = embeddingPort;
    }

    /**
     * Story 8.5: the corpus's chunk embeddings at their settled, ingestion-time
     * 2D positions (Story 8.1). No UI uses it since the Vector Space tab was
     * replaced by the Compare view's similarity ranking; kept for API
     * compatibility until the projection is removed.
     */
    @GetMapping("/api/corpora/{corpusId}/vector-space")
    public ResponseEntity<Map<String, Object>> vectorSpace(@PathVariable("corpusId") String corpusId) {
        Collection<EmbeddedChunk> chunks = vectorStorePort.chunks(corpusId);
        List<Map<String, Object>> chunkPayload = chunks.stream()
                .map(ec -> Map.<String, Object>of(
                        "id", ec.chunk().id(),
                        "x", ec.projection().length > 0 ? ec.projection()[0] : 0.0,
                        "y", ec.projection().length > 1 ? ec.projection()[1] : 0.0))
                .toList();
        return ResponseEntity.ok(Map.of("corpusId", corpusId, "chunks", chunkPayload));
    }

    /**
     * Story 12.6: every retained corpus, ordered most-recently-activated
     * first -- the durable history-list data source for Story 12.7's
     * switcher UI. Offline/demo corpora are excluded by
     * {@link Neo4jCorpusRegistry#list()} itself.
     */
    @GetMapping("/api/corpora")
    public ResponseEntity<Map<String, Object>> corpora() {
        List<Map<String, Object>> corpora = corpusStore.list().stream()
                .map(this::corpusSummaryPayload)
                .toList();
        return ResponseEntity.ok(Map.of("corpora", corpora));
    }

    /**
     * Story 12.6: the only endpoint that ever updates {@code
     * lastActivatedAt} -- never inferred from query/vector-space/progress
     * traffic. 404s via the existing {@code IllegalArgumentException} ->
     * {@link #handleIllegalArgumentException(IllegalArgumentException)}
     * pattern.
     */
    @PostMapping("/api/corpora/{corpusId}/activate")
    public ResponseEntity<Map<String, Object>> activate(@PathVariable("corpusId") String corpusId) {
        corpusStore.get(corpusId)
                .orElseThrow(() -> new IllegalArgumentException("No corpus was found for id " + corpusId));
        corpusStore.activate(corpusId);
        return ResponseEntity.ok(Map.of("id", corpusId, "activated", true));
    }

    /**
     * Deletes a corpus: its graph (Text Units, Entities, Relationships,
     * Communities), its vectors (chunks, projection model) and its registry
     * entry, in that order, so a failure midway leaves the registry entry
     * (and the corpus in the history list) to retry the delete.
     *
     * <p>The server keeps no "active corpus" -- activation is only the most
     * recent {@code lastActivatedAt}. Deleting the active corpus therefore
     * just removes it; the next most recently activated corpus becomes the
     * first list entry, and the web UI switches to it (or to the idle state
     * when none is left). A corpus that is still {@code BUILDING} cannot be
     * deleted (409), since the ingestion would keep writing to it.</p>
     *
     * <p>404 for an unknown id (via the {@code IllegalArgumentException}
     * handler), 204 on success.</p>
     */
    @DeleteMapping("/api/corpora/{corpusId}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable("corpusId") String corpusId) {
        corpusStore.get(corpusId)
                .orElseThrow(() -> new IllegalArgumentException("No corpus was found for id " + corpusId));
        if (corpusStore.status(corpusId) == Neo4jCorpusRegistry.CorpusWorkflowStatus.BUILDING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",
                    "This corpus is still building. Wait until it is ready or failed, then delete it."));
        }
        graphStorePort.deleteCorpus(corpusId);
        vectorStorePort.deleteCorpus(corpusId);
        corpusStore.delete(corpusId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Story 12.7: a bulk read of a corpus's entire graph, shaped exactly
     * like the {@code entity-extracted}/{@code relationship-extracted}/
     * {@code community-detected} SSE payloads already emitted during live
     * ingestion (same field names) -- so the corpus switcher's {@code
     * READY} branch can loop the same {@code GraphCanvas.addEntity}/
     * {@code addRelationship}/{@code addCommunity} functions the live SSE
     * handlers already call, instead of a new bulk-render API (Design
     * Notes). Read-only over the already-real corpus-scoped {@link
     * GraphStorePort} methods -- never touches {@code lastActivatedAt}.
     */
    @GetMapping("/api/corpora/{corpusId}/graph")
    public ResponseEntity<Map<String, Object>> graph(@PathVariable("corpusId") String corpusId) {
        Corpus corpus = corpusStore.get(corpusId)
                .orElseThrow(() -> new IllegalArgumentException("No corpus was found for id " + corpusId));

        Neo4jCorpusRegistry.CorpusWorkflowStatus workflowStatus = corpusStore.status(corpus.id());
        if (workflowStatus == Neo4jCorpusRegistry.CorpusWorkflowStatus.BUILDING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", CorpusMessages.GRAPH_BUILDING_MESSAGE));
        }
        if (workflowStatus == Neo4jCorpusRegistry.CorpusWorkflowStatus.FAILED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", CorpusMessages.GRAPH_FAILED_MESSAGE));
        }

        Collection<Entity> entities = graphStorePort.entities(corpusId);
        Collection<Relationship> relationships = graphStorePort.relationships(corpusId);
        Collection<Community> communities = graphStorePort.communities(corpusId);
        Collection<CommunityMembership> memberships = graphStorePort.communityMemberships(corpusId);
        Map<String, SourceLabel> sourceLabelsById = graphStorePort.textUnits(corpusId).stream()
                .collect(Collectors.toMap(TextUnit::id,
                        unit -> new SourceLabel(unit.documentName(), unit.ordinal()),
                        (first, ignored) -> first,
                        java.util.LinkedHashMap::new));

        Map<String, List<String>> memberEntityIdentitiesByCommunity = memberships.stream()
                .collect(Collectors.groupingBy(
                        CommunityMembership::communityId,
                        Collectors.mapping(CommunityMembership::entityIdentity, Collectors.toList())));

        List<Map<String, Object>> entityPayload = entities.stream()
                .map(entity -> entityEventPayload(entity, sourceLabelsById::get))
                .toList();
        List<Map<String, Object>> relationshipPayload = relationships.stream()
                .map(CorpusEventPayloads::relationshipEventPayload)
                .toList();
        List<Map<String, Object>> communityPayload = communities.stream()
                .map(community -> communityEventPayload(community,
                        memberEntityIdentitiesByCommunity.getOrDefault(community.id(), List.of())))
                .toList();

        return ResponseEntity.ok(Map.of(
                "corpusId", corpusId,
                "entities", entityPayload,
                "relationships", relationshipPayload,
                "communities", communityPayload));
    }

    /**
     * Removes one source document from a corpus: its text units, the entities
     * and relationships only it supported and its vector chunks
     * ({@link UpdateSources#removeBySource}), then re-runs community detection
     * (and, with a semantic embedder, the graph embeddings). 404 for an unknown
     * corpus or document; 409 while the graph is still building.
     */
    @DeleteMapping("/api/corpora/{corpusId}/sources/{documentName}")
    public ResponseEntity<Map<String, Object>> removeSource(@PathVariable("corpusId") String corpusId,
                                                            @PathVariable("documentName") String documentName) {
        Corpus corpus = corpusStore.get(corpusId)
                .orElseThrow(() -> new IllegalArgumentException("No corpus was found for id " + corpusId));
        if (corpusStore.status(corpus.id()) == Neo4jCorpusRegistry.CorpusWorkflowStatus.BUILDING) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", CorpusMessages.GRAPH_BUILDING_MESSAGE));
        }
        Set<String> sourceIds = graphStorePort.textUnits(corpusId).stream()
                .filter(unit -> documentName.equals(unit.documentName()))
                .map(Sources::of)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (sourceIds.isEmpty()) {
            throw new IllegalArgumentException("No document named " + documentName + " was found in corpus " + corpusId);
        }
        UpdateSources updateSources = new UpdateSources(graphStorePort, vectorStorePort, llmPort, embeddingPort);
        int removedTextUnits = 0;
        int removedEntities = 0;
        int removedRelationships = 0;
        for (String sourceId : sourceIds) {
            SourceRemoval removal = updateSources.removeBySource(corpusId, sourceId);
            removedTextUnits += removal.removedTextUnits();
            removedEntities += removal.removedEntities().size() + removal.orphanedEntities().size();
            removedRelationships += removal.removedRelationships();
        }
        updateSources.recomputeCommunities(corpusId);
        if (embeddingPort != null && EmbedGraphElements.isSemantic(embeddingPort)) {
            new EmbedGraphElements(graphStorePort, embeddingPort).run(corpusId);
        }
        return ResponseEntity.ok(Map.of(
                "corpusId", corpusId,
                "documentName", documentName,
                "removedTextUnits", removedTextUnits,
                "removedEntities", removedEntities,
                "removedRelationships", removedRelationships));
    }

    @GetMapping("/api/corpora/{corpusId}/text-units/{textUnitId}")
    public ResponseEntity<Map<String, Object>> textUnit(@PathVariable("corpusId") String corpusId,
                                                        @PathVariable("textUnitId") String textUnitId) {
        corpusStore.get(corpusId)
                .orElseThrow(() -> new IllegalArgumentException("No corpus was found for id " + corpusId));
        TextUnit textUnit = graphStorePort.textUnit(corpusId, textUnitId)
                .orElseThrow(() -> new IllegalArgumentException("No text passage was found for id " + textUnitId));
        return ResponseEntity.ok(Map.of(
                "id", textUnit.id(),
                "documentName", textUnit.documentName() == null ? "" : textUnit.documentName(),
                "ordinal", textUnit.ordinal(),
                "text", textUnit.text() == null ? "" : textUnit.text()));
    }

    /**
     * One vector-index chunk's full text, for the Compare view's vector
     * citations. {@code documentName} is {@code ""} for chunks of corpora
     * indexed before chunks knew their document.
     */
    @GetMapping("/api/corpora/{corpusId}/chunks/{chunkId}")
    public ResponseEntity<Map<String, Object>> chunk(@PathVariable("corpusId") String corpusId,
                                                     @PathVariable("chunkId") String chunkId) {
        corpusStore.get(corpusId)
                .orElseThrow(() -> new IllegalArgumentException("No corpus was found for id " + corpusId));
        Collection<EmbeddedChunk> chunks = vectorStorePort.chunks(corpusId);
        Chunk chunk = (chunks == null ? List.<EmbeddedChunk>of() : chunks).stream()
                .filter(embedded -> embedded != null && embedded.chunk() != null
                        && chunkId.equals(embedded.chunk().id()))
                .map(EmbeddedChunk::chunk)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No vector chunk was found for id " + chunkId));
        return ResponseEntity.ok(Map.of(
                "id", chunk.id(),
                "documentName", chunk.documentName(),
                "ordinal", chunk.ordinal(),
                "text", chunk.text() == null ? "" : chunk.text()));
    }

    private Map<String, Object> corpusSummaryPayload(Neo4jCorpusRegistry.CorpusSummary summary) {
        return Map.of(
                "id", summary.corpusId(),
                "name", summary.name(),
                "status", summary.status().name(),
                "createdAt", summary.createdAt(),
                "lastActivatedAt", summary.lastActivatedAt());
    }
}
