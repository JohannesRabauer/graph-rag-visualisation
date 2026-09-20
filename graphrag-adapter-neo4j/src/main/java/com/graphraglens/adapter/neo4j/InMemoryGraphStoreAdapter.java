package com.graphraglens.adapter.neo4j;

import com.graphraglens.core.domain.Community;
import com.graphraglens.core.domain.CommunityMembership;
import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.Relationship;
import com.graphraglens.core.port.GraphStorePort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory graph-store implementation used to validate the extraction flow.
 */
public class InMemoryGraphStoreAdapter implements GraphStorePort {

    private final Map<String, Entity> entities = new LinkedHashMap<>();
    private final Map<String, Relationship> relationships = new LinkedHashMap<>();
    private final Map<String, Community> communities = new LinkedHashMap<>();
    private final Map<String, CommunityMembership> communityMemberships = new LinkedHashMap<>();
    private final Map<String, Map<String, Entity>> entitiesByCorpusId = new LinkedHashMap<>();
    private final Map<String, Map<String, Relationship>> relationshipsByCorpusId = new LinkedHashMap<>();
    private final Map<String, Map<String, Community>> communitiesByCorpusId = new LinkedHashMap<>();
    private final Map<String, Map<String, CommunityMembership>> communityMembershipsByCorpusId = new LinkedHashMap<>();

    @Override
    public void persistEntities(Collection<Entity> input) {
        if (input == null) {
            return;
        }
        for (Entity entity : input) {
            if (entity == null) {
                continue;
            }
            entities.put(entity.normalizedIdentity(), entity);
        }
    }

    @Override
    public void persistEntities(String corpusId, Collection<Entity> input) {
        if (corpusId == null || corpusId.isBlank()) {
            persistEntities(input);
            return;
        }
        if (input == null) {
            return;
        }
        Map<String, Entity> scoped = scopedMap(entitiesByCorpusId, corpusId);
        for (Entity entity : input) {
            if (entity == null) {
                continue;
            }
            String key = entity.normalizedIdentity();
            entities.put(key, entity);
            scoped.put(key, entity);
        }
    }

    @Override
    public void persistRelationships(Collection<Relationship> input) {
        if (input == null) {
            return;
        }
        for (Relationship relationship : input) {
            if (relationship == null) {
                continue;
            }
            String key = relationship.source() + "::" + relationship.type() + "::" + relationship.target();
            relationships.put(key, relationship);
        }
    }

    @Override
    public void persistRelationships(String corpusId, Collection<Relationship> input) {
        if (corpusId == null || corpusId.isBlank()) {
            persistRelationships(input);
            return;
        }
        if (input == null) {
            return;
        }
        Map<String, Relationship> scoped = scopedMap(relationshipsByCorpusId, corpusId);
        for (Relationship relationship : input) {
            if (relationship == null) {
                continue;
            }
            String key = relationship.source() + "::" + relationship.type() + "::" + relationship.target();
            relationships.put(key, relationship);
            scoped.put(key, relationship);
        }
    }

    @Override
    public void persistCommunities(Collection<Community> input) {
        if (input == null) {
            return;
        }
        for (Community community : input) {
            if (community == null) {
                continue;
            }
            communities.put(community.id(), community);
        }
    }

    @Override
    public void persistCommunities(String corpusId, Collection<Community> input) {
        if (corpusId == null || corpusId.isBlank()) {
            persistCommunities(input);
            return;
        }
        if (input == null) {
            return;
        }
        Map<String, Community> scoped = scopedMap(communitiesByCorpusId, corpusId);
        for (Community community : input) {
            if (community == null) {
                continue;
            }
            communities.put(community.id(), community);
            scoped.put(community.id(), community);
        }
    }

    @Override
    public void persistCommunityMemberships(Collection<CommunityMembership> input) {
        if (input == null) {
            return;
        }
        for (CommunityMembership membership : input) {
            if (membership == null) {
                continue;
            }
            String key = membership.communityId() + "::" + membership.entityIdentity();
            communityMemberships.put(key, membership);
        }
    }

    @Override
    public void persistCommunityMemberships(String corpusId, Collection<CommunityMembership> input) {
        if (corpusId == null || corpusId.isBlank()) {
            persistCommunityMemberships(input);
            return;
        }
        if (input == null) {
            return;
        }
        Map<String, CommunityMembership> scoped = scopedMap(communityMembershipsByCorpusId, corpusId);
        for (CommunityMembership membership : input) {
            if (membership == null) {
                continue;
            }
            String key = membership.communityId() + "::" + membership.entityIdentity();
            communityMemberships.put(key, membership);
            scoped.put(key, membership);
        }
    }

    @Override
    public List<Entity> entities() {
        return new ArrayList<>(entities.values());
    }

    @Override
    public List<Relationship> relationships() {
        return new ArrayList<>(relationships.values());
    }

    @Override
    public List<Community> communities() {
        return new ArrayList<>(communities.values());
    }

    @Override
    public List<CommunityMembership> communityMemberships() {
        return new ArrayList<>(communityMemberships.values());
    }

    @Override
    public List<Entity> entities(String corpusId) {
        if (corpusId == null || corpusId.isBlank()) {
            return entities();
        }
        return new ArrayList<>(readScopedMap(entitiesByCorpusId, corpusId).values());
    }

    @Override
    public List<Relationship> relationships(String corpusId) {
        if (corpusId == null || corpusId.isBlank()) {
            return relationships();
        }
        return new ArrayList<>(readScopedMap(relationshipsByCorpusId, corpusId).values());
    }

    @Override
    public List<Community> communities(String corpusId) {
        if (corpusId == null || corpusId.isBlank()) {
            return communities();
        }
        return new ArrayList<>(readScopedMap(communitiesByCorpusId, corpusId).values());
    }

    @Override
    public List<CommunityMembership> communityMemberships(String corpusId) {
        if (corpusId == null || corpusId.isBlank()) {
            return communityMemberships();
        }
        return new ArrayList<>(readScopedMap(communityMembershipsByCorpusId, corpusId).values());
    }

    private static <T> Map<String, T> scopedMap(Map<String, Map<String, T>> index, String corpusId) {
        return index.computeIfAbsent(corpusId, ignored -> Collections.synchronizedMap(new LinkedHashMap<>()));
    }

    private static <T> Map<String, T> readScopedMap(Map<String, Map<String, T>> index, String corpusId) {
        Map<String, T> scoped = index.get(corpusId);
        return scoped == null ? Collections.emptyMap() : scoped;
    }
}
