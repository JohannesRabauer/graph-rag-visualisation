package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphStorePort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Embeds every persisted Entity and Community of a corpus so the searches can
 * find their starting points by meaning instead of by shared words.
 *
 * <p>Runs after community detection. Only already-persisted text is embedded:
 * {@code name + ": " + description} for an Entity and, by default, the
 * {@code summary} for a Community; {@link Options} can add the Community's
 * title and its most connected members to that text. Nothing happens without
 * a semantic {@link EmbeddingPort} (a null port, or one whose
 * {@link EmbeddingPort#isSemantic()} is {@code false}), so offline ingestion
 * never embeds anything.</p>
 *
 * <p>The texts are sent in batches of {@link Options#batchSize()} through
 * {@link EmbeddingPort#embedAll(List)}: one request per batch for a model with
 * a batch call, one call per text for a port that keeps the default. An
 * embedding failure propagates unchanged; nothing is retried and nothing is
 * persisted for the failing kind of element.</p>
 */
public class EmbedGraphElements {

    private final GraphStorePort graphStorePort;
    private final EmbeddingPort embeddingPort;
    private final Options options;

    public EmbedGraphElements(GraphStorePort graphStorePort, EmbeddingPort embeddingPort) {
        this(graphStorePort, embeddingPort, Options.defaults());
    }

    /** @param options batch size and Community text; null means {@link Options#defaults()} */
    public EmbedGraphElements(GraphStorePort graphStorePort, EmbeddingPort embeddingPort, Options options) {
        this.graphStorePort = graphStorePort;
        this.embeddingPort = embeddingPort;
        this.options = options == null ? Options.defaults() : options;
    }

    /**
     * @return whether {@code embeddingPort} is a real semantic embedding model
     */
    public static boolean isSemantic(EmbeddingPort embeddingPort) {
        return embeddingPort != null && embeddingPort.isSemantic();
    }

    public void run(Corpus corpus) {
        if (corpus == null) {
            return;
        }
        run(corpus.id());
    }

    /** {@link #run(Corpus)} for a corpus known only by its id (e.g. an imported graph). */
    public void run(String corpusId) {
        if (corpusId == null || !isSemantic(embeddingPort)) {
            return;
        }

        Map<String, float[]> entityEmbeddings = embedEntityTexts(orEmpty(graphStorePort.entities(corpusId)));
        Map<String, float[]> communityEmbeddings = embedCommunityTexts(corpusId);

        if (!entityEmbeddings.isEmpty()) {
            graphStorePort.persistEntityEmbeddings(corpusId, entityEmbeddings);
        }
        if (!communityEmbeddings.isEmpty()) {
            graphStorePort.persistCommunityEmbeddings(corpusId, communityEmbeddings);
        }
    }

    /** Embeds only the Communities of {@code corpusId}; nothing without a semantic port. */
    public void embedCommunities(String corpusId) {
        if (corpusId == null || !isSemantic(embeddingPort)) {
            return;
        }
        Map<String, float[]> communityEmbeddings = embedCommunityTexts(corpusId);
        if (!communityEmbeddings.isEmpty()) {
            graphStorePort.persistCommunityEmbeddings(corpusId, communityEmbeddings);
        }
    }

    /**
     * Embeds only {@code entities} of {@code corpusId} (for example the ones an
     * incremental update imported); nothing without a semantic port.
     */
    public void embedEntities(String corpusId, Collection<Entity> entities) {
        if (corpusId == null || entities == null || !isSemantic(embeddingPort)) {
            return;
        }
        Map<String, float[]> embeddings = embedEntityTexts(entities);
        if (!embeddings.isEmpty()) {
            graphStorePort.persistEntityEmbeddings(corpusId, embeddings);
        }
    }

    static String entityText(Entity entity) {
        return entity.name() + ": " + entity.description();
    }

    private Map<String, float[]> embedEntityTexts(Collection<Entity> entities) {
        Map<String, String> texts = new LinkedHashMap<>();
        for (Entity entity : entities) {
            if (entity != null) {
                texts.put(entity.normalizedIdentity(), entityText(entity));
            }
        }
        return embedInBatches(texts);
    }

    private Map<String, float[]> embedCommunityTexts(String corpusId) {
        List<Community> communities = orEmpty(graphStorePort.communities(corpusId)).stream()
                .filter(Objects::nonNull).toList();
        if (communities.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> keyMembers = options.communityKeyMembers() > 0
                ? keyMembers(corpusId, communities) : Map.of();
        Map<String, String> texts = new LinkedHashMap<>();
        for (Community community : communities) {
            texts.put(community.id(), communityText(community, keyMembers.getOrDefault(community.id(), List.of())));
        }
        return embedInBatches(texts);
    }

    /** The summary; with the options also the title in front and the key members behind. */
    private String communityText(Community community, List<String> keyMemberNames) {
        StringBuilder text = new StringBuilder();
        if (options.communityTitle() && !community.title().isBlank()) {
            text.append(community.title()).append(": ");
        }
        text.append(community.summary());
        if (!keyMemberNames.isEmpty()) {
            text.append("\nKey members: ").append(String.join(", ", keyMemberNames));
        }
        return text.toString();
    }

    /**
     * Per Community, the names of its first {@link Options#communityKeyMembers()}
     * members, the ones with the most Relationship weight to the other members
     * first, ties in stored order. Reads the corpus's Entities, Relationships
     * and memberships once.
     */
    private Map<String, List<String>> keyMembers(String corpusId, List<Community> communities) {
        Map<String, Set<String>> membersByCommunity = new LinkedHashMap<>();
        for (CommunityMembership membership : orEmpty(graphStorePort.communityMemberships(corpusId))) {
            if (membership != null) {
                membersByCommunity.computeIfAbsent(membership.communityId(), ignored -> new LinkedHashSet<>())
                        .add(membership.entityIdentity());
            }
        }
        Set<String> wanted = new LinkedHashSet<>();
        membersByCommunity.values().forEach(wanted::addAll);
        Map<String, Entity> entityByIdentity = new HashMap<>();
        for (Entity entity : graphStorePort.entities(corpusId, wanted)) {
            entityByIdentity.putIfAbsent(entity.normalizedIdentity(), entity);
        }
        List<Relationship> relationships = orEmpty(graphStorePort.relationshipsTouching(corpusId, wanted)).stream()
                .filter(Objects::nonNull).toList();

        Map<String, List<String>> names = new HashMap<>();
        for (Community community : communities) {
            Set<String> members = membersByCommunity.getOrDefault(community.id(), Set.of());
            Map<String, Integer> weight = new HashMap<>();
            for (Relationship relationship : relationships) {
                if (members.contains(relationship.sourceIdentity()) && members.contains(relationship.targetIdentity())) {
                    weight.merge(relationship.sourceIdentity(), relationship.weight(), Integer::sum);
                    weight.merge(relationship.targetIdentity(), relationship.weight(), Integer::sum);
                }
            }
            List<String> ranked = new ArrayList<>(members);
            ranked.removeIf(identity -> !entityByIdentity.containsKey(identity));
            // List.sort is stable, so ties keep the stored membership order.
            ranked.sort(Comparator.comparingInt((String identity) -> weight.getOrDefault(identity, 0)).reversed());
            names.put(community.id(), ranked.stream().limit(options.communityKeyMembers())
                    .map(identity -> entityByIdentity.get(identity).name()).toList());
        }
        return names;
    }

    /**
     * Embeds {@code texts} through {@link EmbeddingPort#embedAll(List)}, {@link Options#batchSize()}
     * at a time, and returns the vectors under the same keys, in the same order.
     *
     * @throws IllegalStateException if the port returns another number of vectors than texts
     */
    private Map<String, float[]> embedInBatches(Map<String, String> texts) {
        Map<String, float[]> embeddings = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(texts.keySet());
        for (int from = 0; from < keys.size(); from += options.batchSize()) {
            List<String> batchKeys = keys.subList(from, Math.min(keys.size(), from + options.batchSize()));
            List<String> batch = batchKeys.stream().map(texts::get).toList();
            List<float[]> vectors = embeddingPort.embedAll(batch);
            if (vectors == null || vectors.size() != batch.size()) {
                throw new IllegalStateException("The embedding port returned " + (vectors == null ? "no list"
                        : vectors.size() + " vector(s)") + " for " + batch.size() + " texts");
            }
            for (int i = 0; i < batchKeys.size(); i++) {
                embeddings.put(batchKeys.get(i), vectors.get(i));
            }
        }
        return embeddings;
    }

    private static <T> Collection<T> orEmpty(Collection<T> collection) {
        return collection == null ? List.of() : collection;
    }

    /**
     * How elements are embedded.
     *
     * @param batchSize           the most texts per {@link EmbeddingPort#embedAll(List)}
     *                            call (at least 1; default {@value #DEFAULT_BATCH_SIZE})
     * @param communityTitle      whether a Community's title is embedded in front
     *                            of its summary ({@code title + ": " + summary});
     *                            default {@code false}: the summary alone
     * @param communityKeyMembers how many key members' names are added after the
     *                            summary ({@code "Key members: A, B"}), the ones with
     *                            the most Relationship weight inside the Community
     *                            first (at least 0; default 0: none)
     */
    public record Options(int batchSize, boolean communityTitle, int communityKeyMembers) {

        /** The default number of texts per embedding call. */
        public static final int DEFAULT_BATCH_SIZE = 32;

        public Options {
            if (batchSize < 1) {
                throw new IllegalArgumentException("batchSize must be at least 1, was " + batchSize);
            }
            if (communityKeyMembers < 0) {
                throw new IllegalArgumentException("communityKeyMembers must not be negative, was "
                        + communityKeyMembers);
            }
        }

        /** Batches of {@value #DEFAULT_BATCH_SIZE}; a Community is embedded as its summary alone, as before. */
        public static Options defaults() {
            return new Options(DEFAULT_BATCH_SIZE, false, 0);
        }

        public Options withBatchSize(int value) {
            return new Options(value, communityTitle, communityKeyMembers);
        }

        public Options withCommunityTitle(boolean value) {
            return new Options(batchSize, value, communityKeyMembers);
        }

        public Options withCommunityKeyMembers(int value) {
            return new Options(batchSize, communityTitle, value);
        }
    }
}
