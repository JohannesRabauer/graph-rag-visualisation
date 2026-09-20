package com.graphraglens.web;

import com.graphraglens.core.domain.Community;
import com.graphraglens.core.domain.CommunityMembership;
import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.Relationship;
import com.graphraglens.core.port.GraphStorePort;
import com.graphraglens.core.usecase.ExploreGraph;
import com.graphraglens.core.usecase.ExploreGraphResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/graph} — the bulk, single-shot full-graph read backing
 * Story 6.1's Explore page. A plain REST read: no SSE, no mutation, and it
 * never waits for, locks against, or errors on in-flight ingestion (AD-14)
 * — it simply reflects whatever {@link GraphStorePort} currently holds,
 * which may be empty or partial.
 *
 * <p>Field names deliberately mirror Story 4.3's SSE event payloads
 * ({@code entity-extracted}/{@code relationship-extracted}/
 * {@code community-detected} in {@link CorpusController}) so the Explore
 * page's fetch-then-render code (Story 6.1's {@code explore.js}) can reuse
 * the exact same {@code GraphCanvas.addEntity}/{@code addRelationship}/
 * {@code addCommunity} calls the main screen already makes incrementally
 * from SSE.
 */
@RestController
public class ExploreController {

    private final GraphStorePort graphStorePort;

    public ExploreController(GraphStorePort graphStorePort) {
        this.graphStorePort = graphStorePort;
    }

    @GetMapping("/api/graph")
    public ResponseEntity<Map<String, Object>> graph() {
        ExploreGraphResult result = new ExploreGraph(graphStorePort).explore();

        Map<String, List<String>> memberIdentitiesByCommunityId = new LinkedHashMap<>();
        for (CommunityMembership membership : result.communityMemberships()) {
            memberIdentitiesByCommunityId
                    .computeIfAbsent(membership.communityId(), ignored -> new ArrayList<>())
                    .add(membership.entityIdentity());
        }

        List<Map<String, Object>> entities = result.entities().stream()
                .map(this::entityPayload)
                .toList();
        List<Map<String, Object>> relationships = result.relationships().stream()
                .map(this::relationshipPayload)
                .toList();
        List<Map<String, Object>> communities = result.communities().stream()
                .map(community -> communityPayload(community,
                        memberIdentitiesByCommunityId.getOrDefault(community.id(), List.of())))
                .toList();

        return ResponseEntity.ok(Map.of(
                "entities", entities,
                "relationships", relationships,
                "communities", communities));
    }

    private Map<String, Object> entityPayload(Entity entity) {
        return Map.of(
                "identity", entity.normalizedIdentity(),
                "name", entity.name(),
                "type", entity.type());
    }

    private Map<String, Object> relationshipPayload(Relationship relationship) {
        return Map.of(
                "sourceIdentity", Entity.identityOf(relationship.source(), relationship.sourceType()),
                "source", relationship.source(),
                "targetIdentity", Entity.identityOf(relationship.target(), relationship.targetType()),
                "target", relationship.target(),
                "type", relationship.type());
    }

    private Map<String, Object> communityPayload(Community community, List<String> memberEntityIdentities) {
        return Map.of(
                "communityId", community.id(),
                "summary", community.summary(),
                "memberEntityIdentities", memberEntityIdentities);
    }
}
