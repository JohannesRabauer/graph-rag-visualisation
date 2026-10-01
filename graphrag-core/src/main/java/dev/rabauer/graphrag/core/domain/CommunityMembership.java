package dev.rabauer.graphrag.core.domain;

/**
 * Membership of an entity within a detected community.
 */
public record CommunityMembership(String communityId, String entityIdentity) {
    public CommunityMembership {
        communityId = communityId == null || communityId.isBlank() ? "community-unknown" : communityId.trim();
        entityIdentity = entityIdentity == null ? "" : entityIdentity.trim();
    }
}
