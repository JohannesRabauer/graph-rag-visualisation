package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.CommunitySummary;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.LlmPort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Imports an exact, pre-built knowledge graph — Text Units, Entities and
 * Relationships produced without a model, for example from a bytecode scan —
 * and persists it through the {@link GraphStorePort}. No extraction call is
 * ever made: this is the import path for graphs that are already exact.
 *
 * <p>In order:
 * <ol>
 *   <li>Entities with the same {@link Entity#normalizedIdentity()} are merged
 *       (descriptions and source Text Units joined, the first one's other
 *       fields kept); Relationships with the same
 *       {@code (source identity, type, target identity)} are merged and
 *       their weights summed, so repeated call edges add up.</li>
 *   <li>Relationship endpoints that are neither imported nor already stored
 *       are handled per {@link MissingEndpoints} (default: a placeholder
 *       Entity is created, so the graph stays closed).</li>
 *   <li>Text Units, Entities and Relationships are persisted, in that order.</li>
 *   <li>Communities: caller-supplied ones are persisted as given and
 *       detection is skipped; otherwise {@link DetectCommunities} runs
 *       (unless {@link Options#detectCommunities()} is off), summarizing with
 *       the optional {@link LlmPort} — the only model calls an import makes.</li>
 *   <li>With a semantic {@link EmbeddingPort}, {@link EmbedGraphElements}
 *       embeds the Entities and Communities (unless {@link Options#embed()}
 *       is off).</li>
 * </ol>
 *
 * <p>Store failures propagate unchanged; nothing is retried.
 */
public class ImportKnowledgeGraph {

    private final GraphStorePort graphStorePort;
    private final LlmPort llmPort;
    private final EmbeddingPort embeddingPort;

    /** Imports without a model: deterministic Community summaries, no embeddings. */
    public ImportKnowledgeGraph(GraphStorePort graphStorePort) {
        this(graphStorePort, null, null);
    }

    /**
     * @param llmPort       summarizes detected Communities; null keeps the
     *                      deterministic summaries. Never asked to extract.
     * @param embeddingPort embeds Entities and Communities when semantic;
     *                      null or non-semantic embeds nothing
     */
    public ImportKnowledgeGraph(GraphStorePort graphStorePort, LlmPort llmPort, EmbeddingPort embeddingPort) {
        this.graphStorePort = Objects.requireNonNull(graphStorePort, "graphStorePort");
        this.llmPort = llmPort;
        this.embeddingPort = embeddingPort;
    }

    /** Imports {@code graph} into {@code corpusId} with {@link Options#defaults()}. */
    public ImportResult run(String corpusId, KnowledgeGraphImport graph) {
        return run(corpusId, graph, Options.defaults());
    }

    /**
     * Imports {@code graph} into {@code corpusId}.
     *
     * @throws IllegalArgumentException if {@code corpusId} is null or blank
     */
    public ImportResult run(String corpusId, KnowledgeGraphImport graph, Options options) {
        if (corpusId == null || corpusId.isBlank()) {
            throw new IllegalArgumentException("corpusId must not be null or blank");
        }
        KnowledgeGraphImport input = graph == null ? KnowledgeGraphImport.of(null, null, null) : graph;
        Options effective = options == null ? Options.defaults() : options;

        Map<String, Entity> entities = mergeEntities(input.entities());
        Map<String, Relationship> relationships = mergeRelationships(input.relationships());

        int placeholders = 0;
        int dropped = 0;
        Set<String> missing = missingEndpoints(corpusId, entities, relationships.values());
        if (!missing.isEmpty()) {
            switch (effective.missingEndpoints()) {
                case CREATE -> {
                    for (Relationship relationship : relationships.values()) {
                        placeholders += addPlaceholder(entities, missing,
                                relationship.source(), relationship.sourceType());
                        placeholders += addPlaceholder(entities, missing,
                                relationship.target(), relationship.targetType());
                    }
                }
                case DROP -> {
                    int before = relationships.size();
                    relationships.values().removeIf(relationship -> missing.contains(sourceIdentity(relationship))
                            || missing.contains(targetIdentity(relationship)));
                    dropped = before - relationships.size();
                }
                case KEEP -> {
                    // Persisted as given; the store decides what a dangling endpoint means.
                }
            }
        }

        graphStorePort.persistTextUnits(corpusId, input.textUnits());
        graphStorePort.persistEntities(corpusId, List.copyOf(entities.values()));
        graphStorePort.persistRelationships(corpusId, List.copyOf(relationships.values()));

        List<Community> communities = List.of();
        CommunityDetectionResult detection = null;
        ImportResult.CommunitySource source = ImportResult.CommunitySource.NONE;
        if (input.hasSuppliedCommunities()) {
            communities = persistSuppliedCommunities(corpusId, input, entities);
            source = ImportResult.CommunitySource.SUPPLIED;
        } else if (effective.detectCommunities()) {
            detection = new DetectCommunities(graphStorePort, llmPort, effective.communities()).run(corpusId);
            communities = detection.communities();
            source = communities.isEmpty() ? ImportResult.CommunitySource.NONE
                    : ImportResult.CommunitySource.DETECTED;
        }

        boolean embedded = effective.embed() && EmbedGraphElements.isSemantic(embeddingPort);
        if (embedded) {
            new EmbedGraphElements(graphStorePort, embeddingPort).run(corpusId);
        }

        return new ImportResult(corpusId, input.textUnits().size(), entities.size(), relationships.size(),
                placeholders, dropped, source, communities, embedded, detection);
    }

    private static Map<String, Entity> mergeEntities(List<Entity> input) {
        Map<String, Entity> merged = new LinkedHashMap<>();
        for (Entity entity : input) {
            merged.merge(entity.normalizedIdentity(), entity, GraphElementMerger::merge);
        }
        return merged;
    }

    private static Map<String, Relationship> mergeRelationships(List<Relationship> input) {
        Map<String, Relationship> merged = new LinkedHashMap<>();
        for (Relationship relationship : input) {
            merged.merge(relationshipKey(relationship), relationship, GraphElementMerger::mergeSummingWeights);
        }
        return merged;
    }

    /** Endpoint identities found in neither the import nor the store; reads the store only when needed. */
    private Set<String> missingEndpoints(String corpusId, Map<String, Entity> entities,
                                         Collection<Relationship> relationships) {
        Set<String> missing = new HashSet<>();
        for (Relationship relationship : relationships) {
            for (String identity : List.of(sourceIdentity(relationship), targetIdentity(relationship))) {
                if (!entities.containsKey(identity)) {
                    missing.add(identity);
                }
            }
        }
        if (missing.isEmpty()) {
            return missing;
        }
        Collection<Entity> stored = graphStorePort.entities(corpusId);
        if (stored != null) {
            for (Entity entity : stored) {
                if (entity != null) {
                    missing.remove(entity.normalizedIdentity());
                }
            }
        }
        return missing;
    }

    private static int addPlaceholder(Map<String, Entity> entities, Set<String> missing, String name, String type) {
        String identity = Entity.identityOf(name, type);
        if (!missing.contains(identity) || entities.containsKey(identity)) {
            return 0;
        }
        entities.put(identity, new Entity(name, type));
        return 1;
    }

    /**
     * Persists the caller's Communities as given, plus a deterministic
     * Community for every membership whose Community was not supplied.
     */
    private List<Community> persistSuppliedCommunities(String corpusId, KnowledgeGraphImport input,
                                                       Map<String, Entity> entities) {
        Map<String, Community> byId = new LinkedHashMap<>();
        for (Community community : input.communities()) {
            byId.putIfAbsent(community.id(), community);
        }
        Map<String, List<Entity>> membersById = new LinkedHashMap<>();
        for (CommunityMembership membership : input.memberships()) {
            Entity member = entities.get(membership.entityIdentity());
            List<Entity> members = membersById.computeIfAbsent(membership.communityId(), ignored -> new ArrayList<>());
            if (member != null) {
                members.add(member);
            }
        }
        for (Map.Entry<String, List<Entity>> entry : membersById.entrySet()) {
            if (!byId.containsKey(entry.getKey())) {
                List<Entity> members = entry.getValue();
                byId.put(entry.getKey(), new Community(entry.getKey(),
                        CommunitySummary.deterministicTitle(members), DetectCommunities.fallbackSummary(members)));
            }
        }
        List<Community> communities = List.copyOf(byId.values());
        graphStorePort.persistCommunities(corpusId, communities);
        graphStorePort.persistCommunityMemberships(corpusId, input.memberships());
        return communities;
    }

    private static String sourceIdentity(Relationship relationship) {
        return Entity.identityOf(relationship.source(), relationship.sourceType());
    }

    private static String targetIdentity(Relationship relationship) {
        return Entity.identityOf(relationship.target(), relationship.targetType());
    }

    static String relationshipKey(Relationship relationship) {
        return sourceIdentity(relationship) + "::" + relationship.type().toLowerCase(Locale.ROOT)
                + "::" + targetIdentity(relationship);
    }

    /** What happens to a Relationship endpoint that is neither imported nor already stored. */
    public enum MissingEndpoints {
        /** A placeholder Entity (name and type only) is created and persisted. */
        CREATE,
        /** The Relationship is persisted as given; the store decides. */
        KEEP,
        /** The Relationship is dropped and counted. */
        DROP
    }

    /**
     * How an import runs.
     *
     * @param detectCommunities whether to detect Communities when none are supplied
     * @param embed             whether to embed Entities and Communities (only
     *                          with a semantic {@link EmbeddingPort})
     * @param communities       how {@link DetectCommunities} runs (minimum size,
     *                          detector, parallelism, budget, failure policy,
     *                          summary reuse); null means its defaults
     * @param missingEndpoints  what to do with dangling Relationship endpoints
     */
    public record Options(boolean detectCommunities, boolean embed, DetectCommunities.Options communities,
                          MissingEndpoints missingEndpoints) {

        public Options {
            communities = communities == null ? DetectCommunities.Options.defaults() : communities;
            missingEndpoints = missingEndpoints == null ? MissingEndpoints.CREATE : missingEndpoints;
        }

        /** Detect with {@link DetectCommunities.Options#defaults()}, embed, create placeholders. */
        public static Options defaults() {
            return new Options(true, true, DetectCommunities.Options.defaults(), MissingEndpoints.CREATE);
        }

        public Options withDetectCommunities(boolean value) {
            return new Options(value, embed, communities, missingEndpoints);
        }

        public Options withEmbed(boolean value) {
            return new Options(detectCommunities, value, communities, missingEndpoints);
        }

        /** Shorthand for changing {@link DetectCommunities.Options#minCommunitySize()}. */
        public Options withMinCommunitySize(int value) {
            return new Options(detectCommunities, embed, communities.withMinCommunitySize(value), missingEndpoints);
        }

        public Options withCommunities(DetectCommunities.Options value) {
            return new Options(detectCommunities, embed, value, missingEndpoints);
        }

        public Options withMissingEndpoints(MissingEndpoints value) {
            return new Options(detectCommunities, embed, communities, value);
        }
    }
}
