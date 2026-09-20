package com.graphraglens.web;

import com.graphraglens.adapter.neo4j.InMemoryGraphStoreAdapter;
import com.graphraglens.core.domain.Community;
import com.graphraglens.core.domain.CommunityMembership;
import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.Relationship;
import com.graphraglens.core.port.GraphStorePort;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A plain, non-Spring unit test for {@code GET /api/graph} (Story 6.1's
 * bulk full-graph read).
 *
 * <p>Constructed directly against a fresh {@link InMemoryGraphStoreAdapter}
 * (same reasoning as {@link CorpusControllerGlobalSearchTest}): a shared
 * {@code @SpringBootTest}'s {@code GraphStorePort} bean is a global,
 * unreset singleton across test methods, so it can never reliably observe
 * an empty graph once any other test in a shared context has run.
 */
class ExploreControllerTest {

    @Test
    void emptyGraphStillReturns200WithEmptyArrays() {
        ExploreController controller = new ExploreController(new InMemoryGraphStoreAdapter());

        ResponseEntity<Map<String, Object>> response = controller.graph();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat((List<?>) body.get("entities")).isEmpty();
        assertThat((List<?>) body.get("relationships")).isEmpty();
        assertThat((List<?>) body.get("communities")).isEmpty();
    }

    @Test
    void populatedGraphReturnsEntitiesRelationshipsAndCommunitiesWithMemberIdentities() {
        GraphStorePort graphStorePort = new InMemoryGraphStoreAdapter();

        Entity holmes = new Entity("Sherlock Holmes", "Person");
        Entity watson = new Entity("Dr. Watson", "Person");
        graphStorePort.persistEntities(List.of(holmes, watson));

        Relationship relationship = new Relationship(
                holmes.name(), holmes.type(), "works_with", watson.name(), watson.type());
        graphStorePort.persistRelationships(List.of(relationship));

        Community community = new Community("community-1", "A community about detectives.");
        graphStorePort.persistCommunities(List.of(community));
        graphStorePort.persistCommunityMemberships(List.of(
                new CommunityMembership("community-1", holmes.normalizedIdentity()),
                new CommunityMembership("community-1", watson.normalizedIdentity())));

        ExploreController controller = new ExploreController(graphStorePort);

        ResponseEntity<Map<String, Object>> response = controller.graph();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entities = (List<Map<String, Object>>) body.get("entities");
        assertThat(entities).hasSize(2);
        assertThat(entities).anySatisfy(entity -> {
            assertThat(entity.get("identity")).isEqualTo(holmes.normalizedIdentity());
            assertThat(entity.get("name")).isEqualTo(holmes.name());
            assertThat(entity.get("type")).isEqualTo(holmes.type());
        });

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> relationships = (List<Map<String, Object>>) body.get("relationships");
        assertThat(relationships).hasSize(1);
        Map<String, Object> relationshipPayload = relationships.get(0);
        assertThat(relationshipPayload.get("sourceIdentity")).isEqualTo(holmes.normalizedIdentity());
        assertThat(relationshipPayload.get("source")).isEqualTo(holmes.name());
        assertThat(relationshipPayload.get("targetIdentity")).isEqualTo(watson.normalizedIdentity());
        assertThat(relationshipPayload.get("target")).isEqualTo(watson.name());
        assertThat(relationshipPayload.get("type")).isEqualTo("works_with");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> communities = (List<Map<String, Object>>) body.get("communities");
        assertThat(communities).hasSize(1);
        Map<String, Object> communityPayload = communities.get(0);
        assertThat(communityPayload.get("communityId")).isEqualTo("community-1");
        assertThat(communityPayload.get("summary")).isEqualTo("A community about detectives.");
        @SuppressWarnings("unchecked")
        List<String> memberEntityIdentities = (List<String>) communityPayload.get("memberEntityIdentities");
        assertThat(memberEntityIdentities)
                .containsExactlyInAnyOrder(holmes.normalizedIdentity(), watson.normalizedIdentity());
    }
}
