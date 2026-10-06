package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.Sources;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.port.VectorStorePort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Incremental updates per source (a file, a document): re-index only what
 * changed instead of the whole corpus.
 *
 * <p>{@link #removeBySource(String, String)} deletes, for the source
 * ({@link Sources}: the {@code source} attribute, else the locator's path,
 * else a Text Unit's document name):
 * <ol>
 *   <li>its Text Units;</li>
 *   <li>its Entities (their own source is it), and Entities whose Text Units
 *       were all among the removed ones (extracted only from it);</li>
 *   <li>its Relationships, Relationships touching a removed Entity, and
 *       Relationships whose Text Units were all among the removed ones;</li>
 *   <li>orphans: Entities this removal disconnected (an endpoint of a
 *       removed Relationship, or citing a removed Text Unit) that have no
 *       source of their own, no Text Units left and no remaining Relationship
 *       (for example placeholders for types only the removed file
 *       referenced);</li>
 *   <li>with a {@link VectorStorePort}, the vector index's chunks of the
 *       source.</li>
 * </ol>
 * Kept Entities and Relationships that also cited a removed Text Unit are
 * re-persisted without it. Deleting an Entity deletes its embedding and
 * memberships with it.
 *
 * <p>Derived data that goes stale (reported in {@link SourceRemoval}):
 * Community memberships, summaries, content hashes and embeddings of every
 * Community that lost a member — re-run {@link DetectCommunities#recompute(String)}
 * (with summary reuse, unchanged Communities keep their summaries) and then
 * {@link EmbedGraphElements}. The vector index's projection model is kept.
 *
 * <p>{@link #replaceSource(String, String, KnowledgeGraphImport, ImportKnowledgeGraph.Options)}
 * removes the source and imports its new graph (an upsert by source).
 * Detection is off by default there, so a batch of changed files is imported
 * first and Communities are recomputed once.
 */
public class UpdateSources {

    private final GraphStorePort graphStorePort;
    private final VectorStorePort vectorStorePort;
    private final LlmPort llmPort;
    private final EmbeddingPort embeddingPort;

    public UpdateSources(GraphStorePort graphStorePort) {
        this(graphStorePort, null, null, null);
    }

    /**
     * @param vectorStorePort when given, the source's chunks are deleted too
     * @param llmPort         for {@link #recomputeCommunities(String)} and
     *                        imports that detect; null keeps deterministic summaries
     * @param embeddingPort   for imports and recomputation that embed; null embeds nothing
     */
    public UpdateSources(GraphStorePort graphStorePort, VectorStorePort vectorStorePort, LlmPort llmPort,
                         EmbeddingPort embeddingPort) {
        this.graphStorePort = Objects.requireNonNull(graphStorePort, "graphStorePort");
        this.vectorStorePort = vectorStorePort;
        this.llmPort = llmPort;
        this.embeddingPort = embeddingPort;
    }

    /**
     * Deletes everything that belongs to {@code sourceId} (see the class
     * comment). A source without elements deletes nothing.
     *
     * @throws IllegalArgumentException      if {@code corpusId} or {@code sourceId} is blank
     * @throws UnsupportedOperationException if the store cannot delete
     */
    public SourceRemoval removeBySource(String corpusId, String sourceId) {
        requireNonBlank(corpusId, "corpusId");
        requireNonBlank(sourceId, "sourceId");

        Set<String> removedUnits = new LinkedHashSet<>();
        for (TextUnit unit : nonNull(graphStorePort.textUnits(corpusId))) {
            if (sourceId.equals(Sources.of(unit))) {
                removedUnits.add(unit.id());
            }
        }

        List<Entity> entities = nonNull(graphStorePort.entities(corpusId));
        Map<String, Entity> removedEntities = new LinkedHashMap<>();
        List<Entity> updatedEntities = new ArrayList<>();
        for (Entity entity : entities) {
            if (sourceId.equals(Sources.of(entity)) || citesOnly(entity.sourceTextUnitIds(), removedUnits)) {
                removedEntities.put(entity.normalizedIdentity(), entity);
            } else if (citesAny(entity.sourceTextUnitIds(), removedUnits)) {
                updatedEntities.add(entity.with(entity.name(), entity.type(), entity.description(),
                        without(entity.sourceTextUnitIds(), removedUnits)));
            }
        }

        List<Relationship> relationships = nonNull(graphStorePort.relationships(corpusId));
        List<Relationship> removedRelationships = new ArrayList<>();
        List<Relationship> keptRelationships = new ArrayList<>();
        List<Relationship> updatedRelationships = new ArrayList<>();
        for (Relationship relationship : relationships) {
            if (sourceId.equals(Sources.of(relationship))
                    || removedEntities.containsKey(relationship.sourceIdentity())
                    || removedEntities.containsKey(relationship.targetIdentity())
                    || citesOnly(relationship.sourceTextUnitIds(), removedUnits)) {
                removedRelationships.add(relationship);
            } else {
                keptRelationships.add(relationship);
                if (citesAny(relationship.sourceTextUnitIds(), removedUnits)) {
                    List<String> remaining = without(relationship.sourceTextUnitIds(), removedUnits);
                    updatedRelationships.add(relationship.with(relationship.source(), relationship.sourceType(),
                            relationship.target(), relationship.targetType(), relationship.description(), remaining,
                            relationship.weight()));
                }
            }
        }

        // Orphans: Entities this removal touched (an endpoint of a removed Relationship, or a removed Text
        // Unit cited it) that have no own source, no Text Units left and no remaining Relationship.
        Set<String> touched = new HashSet<>();
        for (Relationship relationship : removedRelationships) {
            touched.add(relationship.sourceIdentity());
            touched.add(relationship.targetIdentity());
        }
        for (Entity updated : updatedEntities) {
            touched.add(updated.normalizedIdentity());
        }
        Set<String> stillConnected = new HashSet<>();
        for (Relationship relationship : keptRelationships) {
            stillConnected.add(relationship.sourceIdentity());
            stillConnected.add(relationship.targetIdentity());
        }
        Set<String> updatedIdentities = new HashSet<>();
        for (Entity updated : updatedEntities) {
            updatedIdentities.add(updated.normalizedIdentity());
        }
        List<String> orphans = new ArrayList<>();
        for (Entity entity : entities) {
            String identity = entity.normalizedIdentity();
            boolean hasUnits = updatedIdentities.contains(identity)
                    ? updatedEntities.stream().anyMatch(updated -> updated.normalizedIdentity().equals(identity)
                    && !updated.sourceTextUnitIds().isEmpty())
                    : !entity.sourceTextUnitIds().isEmpty();
            if (touched.contains(identity) && !removedEntities.containsKey(identity) && Sources.of(entity).isEmpty()
                    && !hasUnits && !stillConnected.contains(identity)) {
                removedEntities.put(identity, entity);
                orphans.add(identity);
            }
        }
        updatedEntities.removeIf(updated -> removedEntities.containsKey(updated.normalizedIdentity()));

        List<String> staleCommunities = new ArrayList<>();
        for (CommunityMembership membership : nonNull(graphStorePort.communityMemberships(corpusId))) {
            if (removedEntities.containsKey(membership.entityIdentity())
                    && !staleCommunities.contains(membership.communityId())) {
                staleCommunities.add(membership.communityId());
            }
        }

        if (!removedRelationships.isEmpty()) {
            graphStorePort.deleteRelationships(corpusId, removedRelationships);
        }
        if (!removedEntities.isEmpty()) {
            graphStorePort.deleteEntities(corpusId, List.copyOf(removedEntities.keySet()));
        }
        if (!removedUnits.isEmpty()) {
            graphStorePort.deleteTextUnits(corpusId, removedUnits);
        }
        if (!updatedEntities.isEmpty()) {
            graphStorePort.persistEntities(corpusId, updatedEntities);
        }
        if (!updatedRelationships.isEmpty()) {
            graphStorePort.persistRelationships(corpusId, updatedRelationships);
        }
        boolean removedChunks = false;
        if (vectorStorePort != null) {
            vectorStorePort.deleteChunksOf(corpusId, sourceId);
            removedChunks = true;
        }

        return new SourceRemoval(corpusId, sourceId, removedUnits.size(), List.copyOf(removedEntities.keySet()),
                orphans, removedRelationships.size(), updatedEntities.size(), updatedRelationships.size(),
                removedChunks, staleCommunities);
    }

    /**
     * Upsert by source: {@link #removeBySource(String, String)}, then
     * {@link ImportKnowledgeGraph} of the source's new graph. Null options
     * mean {@link ImportKnowledgeGraph.Options#defaults()} without detection
     * (recompute once after a batch with {@link #recomputeCommunities(String)}).
     *
     * @return the removal and the import
     */
    public Replacement replaceSource(String corpusId, String sourceId, KnowledgeGraphImport graph,
                                     ImportKnowledgeGraph.Options options) {
        SourceRemoval removal = removeBySource(corpusId, sourceId);
        ImportKnowledgeGraph.Options effective = options == null
                ? ImportKnowledgeGraph.Options.defaults().withDetectCommunities(false)
                : options;
        ImportResult imported = new ImportKnowledgeGraph(graphStorePort, llmPort, embeddingPort)
                .run(corpusId, graph, effective);
        return new Replacement(removal, imported);
    }

    /**
     * Re-detects the Communities of {@code corpusId} from scratch
     * ({@link DetectCommunities#recompute(String)} with summary reuse) and,
     * with a semantic embedding port, re-embeds the corpus.
     */
    public CommunityDetectionResult recomputeCommunities(String corpusId) {
        return recomputeCommunities(corpusId, DetectCommunities.Options.defaults().withReuseSummaries(true));
    }

    /** {@link #recomputeCommunities(String)} with explicit detection options. */
    public CommunityDetectionResult recomputeCommunities(String corpusId, DetectCommunities.Options options) {
        CommunityDetectionResult result = new DetectCommunities(graphStorePort, llmPort, options).recompute(corpusId);
        new EmbedGraphElements(graphStorePort, embeddingPort).run(corpusId);
        return result;
    }

    /** The outcome of {@link #replaceSource}. */
    public record Replacement(SourceRemoval removal, ImportResult imported) {
    }

    private static boolean citesOnly(List<String> unitIds, Set<String> removedUnits) {
        return !unitIds.isEmpty() && !removedUnits.isEmpty() && removedUnits.containsAll(unitIds);
    }

    private static boolean citesAny(List<String> unitIds, Set<String> removedUnits) {
        for (String unitId : unitIds) {
            if (removedUnits.contains(unitId)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> without(List<String> unitIds, Set<String> removedUnits) {
        return unitIds.stream().filter(unitId -> !removedUnits.contains(unitId)).toList();
    }

    private static <T> List<T> nonNull(Collection<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
    }
}
