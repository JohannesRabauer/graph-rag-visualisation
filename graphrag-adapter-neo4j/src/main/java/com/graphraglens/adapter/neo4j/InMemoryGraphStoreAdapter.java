package com.graphraglens.adapter.neo4j;

import com.graphraglens.core.domain.Community;
import com.graphraglens.core.domain.CommunityMembership;
import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.Relationship;
import com.graphraglens.core.port.GraphStorePort;

import java.util.ArrayList;
import java.util.Collection;
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

    public List<Entity> entities() {
        return new ArrayList<>(entities.values());
    }

    public List<Relationship> relationships() {
        return new ArrayList<>(relationships.values());
    }

    public List<Community> communities() {
        return new ArrayList<>(communities.values());
    }

    public List<CommunityMembership> communityMemberships() {
        return new ArrayList<>(communityMemberships.values());
    }
}
