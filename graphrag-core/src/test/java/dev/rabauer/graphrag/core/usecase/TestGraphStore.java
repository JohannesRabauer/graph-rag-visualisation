package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.port.GraphStorePort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A corpus-scoped, in-memory {@link GraphStorePort} for use-case tests:
 * every write replaces by key (Entity identity, Relationship key, Community
 * id, Text Unit id), reads keep insertion order, and the community detection
 * is the port's default.
 */
class TestGraphStore implements GraphStorePort {

    final Map<String, Map<String, TextUnit>> textUnits = new LinkedHashMap<>();
    final Map<String, Map<String, Entity>> entities = new LinkedHashMap<>();
    final Map<String, Map<String, Relationship>> relationships = new LinkedHashMap<>();
    final Map<String, Map<String, Community>> communities = new LinkedHashMap<>();
    final Map<String, Map<String, CommunityMembership>> memberships = new LinkedHashMap<>();
    final Map<String, Map<String, float[]>> entityEmbeddings = new LinkedHashMap<>();
    final Map<String, Map<String, float[]>> communityEmbeddings = new LinkedHashMap<>();
    int entityReads;

    @Override
    public void persistEntities(Collection<Entity> input) {
        throw new UnsupportedOperationException("corpus-scoped only");
    }

    @Override
    public void persistRelationships(Collection<Relationship> input) {
        throw new UnsupportedOperationException("corpus-scoped only");
    }

    @Override
    public void persistTextUnits(String corpusId, Collection<TextUnit> input) {
        input.forEach(unit -> scoped(textUnits, corpusId).put(unit.id(), unit));
    }

    @Override
    public void persistEntities(String corpusId, Collection<Entity> input) {
        input.forEach(entity -> scoped(entities, corpusId).put(entity.normalizedIdentity(), entity));
    }

    @Override
    public void persistRelationships(String corpusId, Collection<Relationship> input) {
        input.forEach(relationship -> scoped(relationships, corpusId)
                .put(ImportKnowledgeGraph.relationshipKey(relationship), relationship));
    }

    @Override
    public void persistCommunities(String corpusId, Collection<Community> input) {
        input.forEach(community -> scoped(communities, corpusId).put(community.id(), community));
    }

    @Override
    public void persistCommunityMemberships(String corpusId, Collection<CommunityMembership> input) {
        input.forEach(membership -> scoped(memberships, corpusId)
                .put(membership.communityId() + "::" + membership.entityIdentity(), membership));
    }

    @Override
    public void persistEntityEmbeddings(String corpusId, Map<String, float[]> byIdentity) {
        scoped(entityEmbeddings, corpusId).putAll(byIdentity);
    }

    @Override
    public void persistCommunityEmbeddings(String corpusId, Map<String, float[]> byCommunityId) {
        scoped(communityEmbeddings, corpusId).putAll(byCommunityId);
    }

    @Override
    public void deleteTextUnits(String corpusId, Collection<String> textUnitIds) {
        scoped(textUnits, corpusId).keySet().removeAll(textUnitIds);
    }

    @Override
    public void deleteEntities(String corpusId, Collection<String> identities) {
        scoped(entities, corpusId).keySet().removeAll(identities);
        scoped(entityEmbeddings, corpusId).keySet().removeAll(identities);
        scoped(relationships, corpusId).values().removeIf(relationship ->
                identities.contains(relationship.sourceIdentity()) || identities.contains(relationship.targetIdentity()));
        scoped(memberships, corpusId).values().removeIf(membership -> identities.contains(membership.entityIdentity()));
    }

    @Override
    public void deleteRelationships(String corpusId, Collection<Relationship> input) {
        for (Relationship relationship : input) {
            scoped(relationships, corpusId).remove(ImportKnowledgeGraph.relationshipKey(relationship));
        }
    }

    @Override
    public void deleteCommunities(String corpusId) {
        scoped(communities, corpusId).clear();
        scoped(memberships, corpusId).clear();
        scoped(communityEmbeddings, corpusId).clear();
    }

    @Override
    public Collection<TextUnit> textUnits(String corpusId) {
        return List.copyOf(scoped(textUnits, corpusId).values());
    }

    @Override
    public Optional<TextUnit> textUnit(String corpusId, String textUnitId) {
        return Optional.ofNullable(scoped(textUnits, corpusId).get(textUnitId));
    }

    @Override
    public Collection<Entity> entities(String corpusId) {
        entityReads++;
        return List.copyOf(scoped(entities, corpusId).values());
    }

    @Override
    public Collection<Relationship> relationships(String corpusId) {
        return List.copyOf(scoped(relationships, corpusId).values());
    }

    @Override
    public Collection<Community> communities(String corpusId) {
        return List.copyOf(scoped(communities, corpusId).values());
    }

    @Override
    public Collection<CommunityMembership> communityMemberships(String corpusId) {
        return List.copyOf(scoped(memberships, corpusId).values());
    }

    /** The stored Entity with {@code identity}, or null (a shorthand for tests). */
    Entity stored(String corpusId, String identity) {
        return scoped(entities, corpusId).get(identity);
    }

    List<String> memberIdentities(String corpusId, String communityId) {
        List<String> members = new ArrayList<>();
        for (CommunityMembership membership : communityMemberships(corpusId)) {
            if (membership.communityId().equals(communityId)) {
                members.add(membership.entityIdentity());
            }
        }
        return members;
    }

    private static <T> Map<String, T> scoped(Map<String, Map<String, T>> index, String corpusId) {
        return index.computeIfAbsent(corpusId, ignored -> new LinkedHashMap<>());
    }
}
