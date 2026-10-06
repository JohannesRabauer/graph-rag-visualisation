package dev.rabauer.graphrag.testkit;

import dev.rabauer.graphrag.core.community.CommunityDetector;
import dev.rabauer.graphrag.core.community.GraphCommunities;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.port.GraphStorePort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A complete, corpus-scoped reference {@link GraphStorePort} kept in memory,
 * for tests of applications built on {@code graphrag-core}. Writes replace by
 * key (Entity identity, Relationship {@code (source, type, target)}, Text Unit
 * id, Community id, membership), reads keep insertion order, similarity is
 * exact cosine over the persisted embeddings, and Communities are detected
 * with a configurable core detector. The unscoped legacy writes are rejected.
 * Thread-safe (every method is synchronized); not meant for large graphs.
 */
public class InMemoryGraphStore implements GraphStorePort {

    private final CommunityDetector communityDetector;
    private final Map<String, Corpus> corpora = new LinkedHashMap<>();

    public InMemoryGraphStore() {
        this(GraphCommunities.defaultDetector());
    }

    public InMemoryGraphStore(CommunityDetector communityDetector) {
        this.communityDetector = Objects.requireNonNull(communityDetector, "communityDetector");
    }

    /** One corpus's data. */
    private static final class Corpus {
        final Map<String, TextUnit> textUnits = new LinkedHashMap<>();
        final Map<String, Entity> entities = new LinkedHashMap<>();
        final Map<String, Relationship> relationships = new LinkedHashMap<>();
        final Map<String, Community> communities = new LinkedHashMap<>();
        final Map<String, CommunityMembership> memberships = new LinkedHashMap<>();
        final Map<String, float[]> entityEmbeddings = new LinkedHashMap<>();
        final Map<String, float[]> communityEmbeddings = new LinkedHashMap<>();
    }

    // -- Writes ---------------------------------------------------------------------

    @Override
    public void persistEntities(Collection<Entity> entities) {
        throw new UnsupportedOperationException("InMemoryGraphStore is corpus-scoped; use persistEntities(corpusId, ...)");
    }

    @Override
    public void persistRelationships(Collection<Relationship> relationships) {
        throw new UnsupportedOperationException(
                "InMemoryGraphStore is corpus-scoped; use persistRelationships(corpusId, ...)");
    }

    @Override
    public synchronized void persistTextUnits(String corpusId, Collection<TextUnit> textUnits) {
        Corpus corpus = writable(corpusId);
        for (TextUnit unit : nonNull(textUnits)) {
            corpus.textUnits.put(unit.id(), unit);
        }
    }

    @Override
    public synchronized void persistEntities(String corpusId, Collection<Entity> entities) {
        Corpus corpus = writable(corpusId);
        for (Entity entity : nonNull(entities)) {
            corpus.entities.put(entity.normalizedIdentity(), entity);
        }
    }

    @Override
    public synchronized void persistRelationships(String corpusId, Collection<Relationship> relationships) {
        Corpus corpus = writable(corpusId);
        for (Relationship relationship : nonNull(relationships)) {
            corpus.relationships.put(relationshipKey(relationship), relationship);
        }
    }

    @Override
    public synchronized void retypeEntity(String corpusId, String previousIdentity, Entity resolved) {
        if (previousIdentity == null || resolved == null) {
            return;
        }
        Corpus corpus = writable(corpusId);
        corpus.entities.remove(previousIdentity);
        corpus.entities.put(resolved.normalizedIdentity(), resolved);
        Map<String, Relationship> rewritten = new LinkedHashMap<>();
        for (Relationship relationship : corpus.relationships.values()) {
            boolean source = relationship.sourceIdentity().equals(previousIdentity);
            boolean target = relationship.targetIdentity().equals(previousIdentity);
            Relationship updated = !source && !target ? relationship : relationship.with(
                    source ? resolved.name() : relationship.source(), source ? resolved.type() : relationship.sourceType(),
                    target ? resolved.name() : relationship.target(), target ? resolved.type() : relationship.targetType(),
                    relationship.description(), relationship.sourceTextUnitIds(), relationship.weight());
            rewritten.put(relationshipKey(updated), updated);
        }
        corpus.relationships.clear();
        corpus.relationships.putAll(rewritten);
    }

    @Override
    public synchronized void persistCommunities(String corpusId, Collection<Community> communities) {
        Corpus corpus = writable(corpusId);
        for (Community community : nonNull(communities)) {
            corpus.communities.put(community.id(), community);
        }
    }

    @Override
    public synchronized void persistCommunityMemberships(String corpusId, Collection<CommunityMembership> memberships) {
        Corpus corpus = writable(corpusId);
        for (CommunityMembership membership : nonNull(memberships)) {
            corpus.memberships.put(membership.communityId() + "::" + membership.entityIdentity(), membership);
        }
    }

    @Override
    public synchronized void persistEntityEmbeddings(String corpusId, Map<String, float[]> byIdentity) {
        if (byIdentity != null) {
            writable(corpusId).entityEmbeddings.putAll(byIdentity);
        }
    }

    @Override
    public synchronized void persistCommunityEmbeddings(String corpusId, Map<String, float[]> byCommunityId) {
        if (byCommunityId != null) {
            writable(corpusId).communityEmbeddings.putAll(byCommunityId);
        }
    }

    @Override
    public synchronized void deleteTextUnits(String corpusId, Collection<String> textUnitIds) {
        if (textUnitIds != null) {
            readable(corpusId).textUnits.keySet().removeAll(textUnitIds);
        }
    }

    @Override
    public synchronized void deleteEntities(String corpusId, Collection<String> identities) {
        if (identities == null) {
            return;
        }
        Corpus corpus = readable(corpusId);
        corpus.entities.keySet().removeAll(identities);
        corpus.entityEmbeddings.keySet().removeAll(identities);
        corpus.relationships.values().removeIf(relationship -> identities.contains(relationship.sourceIdentity())
                || identities.contains(relationship.targetIdentity()));
        corpus.memberships.values().removeIf(membership -> identities.contains(membership.entityIdentity()));
    }

    @Override
    public synchronized void deleteRelationships(String corpusId, Collection<Relationship> relationships) {
        Corpus corpus = readable(corpusId);
        for (Relationship relationship : nonNull(relationships)) {
            corpus.relationships.remove(relationshipKey(relationship));
        }
    }

    @Override
    public synchronized void deleteCommunities(String corpusId) {
        Corpus corpus = readable(corpusId);
        corpus.communities.clear();
        corpus.memberships.clear();
        corpus.communityEmbeddings.clear();
    }

    @Override
    public synchronized List<List<String>> detectCommunities(String corpusId) {
        return GraphCommunities.detect(communityDetector, entities(corpusId), relationships(corpusId));
    }

    // -- Reads ----------------------------------------------------------------------

    @Override
    public synchronized List<TextUnit> textUnits(String corpusId) {
        return List.copyOf(readable(corpusId).textUnits.values());
    }

    @Override
    public synchronized Optional<TextUnit> textUnit(String corpusId, String textUnitId) {
        return Optional.ofNullable(readable(corpusId).textUnits.get(textUnitId));
    }

    @Override
    public synchronized List<Entity> entities(String corpusId) {
        return List.copyOf(readable(corpusId).entities.values());
    }

    @Override
    public synchronized Optional<Entity> entity(String corpusId, String identity) {
        return Optional.ofNullable(readable(corpusId).entities.get(identity));
    }

    @Override
    public synchronized List<Relationship> relationships(String corpusId) {
        return List.copyOf(readable(corpusId).relationships.values());
    }

    @Override
    public synchronized List<Community> communities(String corpusId) {
        return List.copyOf(readable(corpusId).communities.values());
    }

    @Override
    public synchronized List<CommunityMembership> communityMemberships(String corpusId) {
        return List.copyOf(readable(corpusId).memberships.values());
    }

    @Override
    public synchronized List<Entity> similarEntities(String corpusId, float[] query, int k) {
        Corpus corpus = readable(corpusId);
        return corpus.entities.values().stream()
                .filter(entity -> corpus.entityEmbeddings.containsKey(entity.normalizedIdentity()))
                .sorted(Comparator.comparingDouble((Entity entity) ->
                        cosine(query, corpus.entityEmbeddings.get(entity.normalizedIdentity()))).reversed())
                .limit(Math.max(0, k))
                .toList();
    }

    @Override
    public synchronized List<Community> similarCommunities(String corpusId, float[] query, int k) {
        Corpus corpus = readable(corpusId);
        return corpus.communities.values().stream()
                .filter(community -> corpus.communityEmbeddings.containsKey(community.id()))
                .sorted(Comparator.comparingDouble((Community community) ->
                        cosine(query, corpus.communityEmbeddings.get(community.id()))).reversed())
                .limit(Math.max(0, k))
                .toList();
    }

    // -- Helpers --------------------------------------------------------------------

    private Corpus writable(String corpusId) {
        if (corpusId == null || corpusId.isBlank()) {
            throw new IllegalArgumentException("corpusId must not be null or blank");
        }
        return corpora.computeIfAbsent(corpusId, ignored -> new Corpus());
    }

    private Corpus readable(String corpusId) {
        Corpus corpus = corpusId == null ? null : corpora.get(corpusId);
        return corpus == null ? new Corpus() : corpus;
    }

    static String relationshipKey(Relationship relationship) {
        return relationship.sourceIdentity() + "::" + relationship.type().toLowerCase(Locale.ROOT) + "::"
                + relationship.targetIdentity();
    }

    private static <T> List<T> nonNull(Collection<T> values) {
        return values == null ? List.of() : new ArrayList<>(values.stream().filter(Objects::nonNull).toList());
    }

    private static double cosine(float[] left, float[] right) {
        if (left == null || right == null) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
    }
}
