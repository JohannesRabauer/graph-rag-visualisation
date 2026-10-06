package dev.rabauer.graphrag.core.port;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.TextUnit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The read side of the knowledge graph: everything the query use cases
 * (Local, Global, DRIFT Search, their retrieval-only variants and the seed
 * matchers) need. An application that already has its own graph can
 * implement only this port over it and run every query.
 *
 * <p>Every method has a default, so an implementation provides only what its
 * store supports: the corpus-scoped reads, Text Unit lookup and the
 * similarity lookups are the ones the use cases call. The
 * {@link #entity(String, String)}, {@link #entities(String, Collection)} and
 * {@link #relationshipsTouching(String, Collection)} defaults filter the
 * full corpus reads; a large store should override them with indexed
 * lookups, because Local expansion calls them once per hop.
 *
 * <p>Contract (checked by {@code GraphReadPortContract} in
 * {@code graphrag-core-testkit}): reads never return null, are scoped to their
 * corpus, keep a stable order across calls, and return elements with their
 * attributes and locators.
 */
public interface GraphReadPort {

    /**
     * @return the persisted Text Units of {@code corpusId}; empty by default
     */
    default Collection<TextUnit> textUnits(String corpusId) {
        return List.of();
    }

    default Optional<TextUnit> textUnit(String corpusId, String textUnitId) {
        if (corpusId == null || corpusId.isBlank() || textUnitId == null || textUnitId.isBlank()) {
            return Optional.empty();
        }
        return textUnits(corpusId).stream()
                .filter(textUnit -> textUnitId.equals(textUnit.id()))
                .findFirst();
    }

    default Collection<Entity> entities() {
        return List.of();
    }

    default Collection<Entity> entities(String corpusId) {
        return entities();
    }

    /**
     * The Entity of {@code corpusId} with {@code identity}
     * ({@link Entity#normalizedIdentity()}); the default filters
     * {@link #entities(String)}.
     */
    default Optional<Entity> entity(String corpusId, String identity) {
        if (identity == null) {
            return Optional.empty();
        }
        return entities(corpusId).stream()
                .filter(entity -> entity != null && identity.equals(entity.normalizedIdentity()))
                .findFirst();
    }

    /**
     * The Entities of {@code corpusId} with one of {@code identities}, in
     * stored order; the default filters {@link #entities(String)}.
     */
    default List<Entity> entities(String corpusId, Collection<String> identities) {
        if (identities == null || identities.isEmpty()) {
            return List.of();
        }
        Set<String> wanted = new HashSet<>(identities);
        List<Entity> found = new ArrayList<>();
        for (Entity entity : entities(corpusId)) {
            if (entity != null && wanted.contains(entity.normalizedIdentity())) {
                found.add(entity);
            }
        }
        return found;
    }

    default Collection<Relationship> relationships() {
        return List.of();
    }

    default Collection<Relationship> relationships(String corpusId) {
        return relationships();
    }

    /**
     * The Relationships of {@code corpusId} whose source or target identity is
     * one of {@code identities}, in stored order; the default filters
     * {@link #relationships(String)}.
     */
    default List<Relationship> relationshipsTouching(String corpusId, Collection<String> identities) {
        if (identities == null || identities.isEmpty()) {
            return List.of();
        }
        Set<String> wanted = new HashSet<>(identities);
        List<Relationship> found = new ArrayList<>();
        for (Relationship relationship : relationships(corpusId)) {
            if (relationship != null && (wanted.contains(relationship.sourceIdentity())
                    || wanted.contains(relationship.targetIdentity()))) {
                found.add(relationship);
            }
        }
        return found;
    }

    default Collection<Community> communities() {
        return List.of();
    }

    default Collection<Community> communities(String corpusId) {
        return communities();
    }

    default Collection<CommunityMembership> communityMemberships() {
        return List.of();
    }

    default Collection<CommunityMembership> communityMemberships(String corpusId) {
        return communityMemberships();
    }

    /**
     * @return at most {@code k} Entities of {@code corpusId}, most similar to
     *         {@code query} first; empty by default or when the corpus has no
     *         embedded Entities (callers then fall back to keyword matching).
     *         The vectors may live anywhere (a graph vector index, pgvector, …).
     */
    default List<Entity> similarEntities(String corpusId, float[] query, int k) {
        return List.of();
    }

    /**
     * @return at most {@code k} Communities of {@code corpusId}, most similar
     *         to {@code query} first; empty by default or when the corpus has
     *         no embedded Communities (callers then fall back to keyword
     *         matching)
     */
    default List<Community> similarCommunities(String corpusId, float[] query, int k) {
        return List.of();
    }
}
