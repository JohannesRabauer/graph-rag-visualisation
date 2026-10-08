package dev.rabauer.graphrag.core.domain;

import java.util.Objects;

/**
 * One key point a Community's content contributes to a Global Search
 * question: the map step of map-reduce Global Search.
 *
 * @param communityId the {@link Community#id()} the point comes from; never null
 * @param text        the point, in one or two sentences; never null
 * @param score       how much it helps answer the question, from 0 (not at
 *                    all) to 100 (answers it directly); clamped to that range
 */
public record CommunityPoint(String communityId, String text, int score) {

    public CommunityPoint {
        Objects.requireNonNull(communityId, "communityId must not be null");
        text = text == null ? "" : text.trim();
        score = Math.max(0, Math.min(100, score));
    }
}
