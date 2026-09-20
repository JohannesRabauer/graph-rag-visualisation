package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Community;
import com.graphraglens.core.domain.CommunityMembership;
import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.Relationship;

import java.util.List;

/**
 * Result of {@link ExploreGraph} — the full, unscoped Knowledge Graph read
 * for Story 6.1's Explore page: every persisted Entity, Relationship,
 * Community, and Community membership, grouped for the web layer to shape
 * into {@code GET /api/graph}'s response.
 */
public record ExploreGraphResult(
        List<Entity> entities,
        List<Relationship> relationships,
        List<Community> communities,
        List<CommunityMembership> communityMemberships) {

    public ExploreGraphResult {
        entities = entities == null ? List.of() : List.copyOf(entities);
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
        communities = communities == null ? List.of() : List.copyOf(communities);
        communityMemberships = communityMemberships == null ? List.of() : List.copyOf(communityMemberships);
    }
}
