package dev.rabauer.graphrag.testkit;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.port.GraphReadPort;
import dev.rabauer.graphrag.core.port.GraphWritePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static dev.rabauer.graphrag.testkit.ContractGraph.ALPHA;
import static dev.rabauer.graphrag.testkit.ContractGraph.BETA;
import static dev.rabauer.graphrag.testkit.ContractGraph.CALL_AT;
import static dev.rabauer.graphrag.testkit.ContractGraph.DELTA;
import static dev.rabauer.graphrag.testkit.ContractGraph.GAMMA;
import static dev.rabauer.graphrag.testkit.ContractGraph.RUN;
import static dev.rabauer.graphrag.testkit.ContractGraph.identity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract every {@link GraphReadPort} must meet for the query use cases
 * to work on it. Extend it in a test of your adapter and load the
 * {@link ContractGraph} into your store in {@link #givenGraph(ContractGraph)}
 * — through your own schema if your adapter only reads an existing graph.
 *
 * <pre>{@code
 * class MyGraphReadPortTest extends GraphReadPortContract {
 *     protected GraphReadPort givenGraph(ContractGraph graph) {
 *         loadIntoMyDatabase(graph);          // however your graph is written
 *         return new MyGraphReadPort(...);
 *     }
 * }
 * }</pre>
 */
public abstract class GraphReadPortContract {

    /** The fixture of the current test (fresh corpus ids per test). */
    protected ContractGraph graph;
    /** The port under test, loaded with {@link #graph}. */
    protected GraphReadPort port;

    /**
     * Makes {@code graph} (both corpora: Text Units, Entities, Relationships,
     * Communities, memberships) readable and returns the port reading it.
     */
    protected abstract GraphReadPort givenGraph(ContractGraph graph);

    @BeforeEach
    void loadContractGraph() {
        graph = ContractGraph.create();
        port = givenGraph(graph);
    }

    @Test
    void entitiesAreScopedToTheirCorpus() {
        assertEquals(Set.of(ALPHA, RUN, BETA, GAMMA), names(port.entities(graph.corpusId())));
        assertEquals(Set.of(DELTA), names(port.entities(graph.otherCorpusId())));
    }

    @Test
    void entitiesKeepTheirFieldsAttributesAndLocators() {
        Entity alpha = only(port.entities(graph.corpusId()), ALPHA);
        Entity expected = graph.entity(ALPHA);

        assertEquals(expected.type(), alpha.type());
        assertEquals(expected.description(), alpha.description());
        assertEquals(expected.normalizedIdentity(), alpha.normalizedIdentity());
        assertEquals(Set.copyOf(expected.sourceTextUnitIds()), Set.copyOf(alpha.sourceTextUnitIds()));
        assertEquals(expected.attributes(), alpha.attributes());
        assertEquals(expected.locator(), alpha.locator());

        Entity gamma = only(port.entities(graph.corpusId()), GAMMA);
        assertEquals(Map.of(), gamma.attributes());
        assertNull(gamma.locator());
    }

    @Test
    void relationshipsKeepTheirTypeWeightAttributesAndLocators() {
        Collection<Relationship> relationships = port.relationships(graph.corpusId());

        assertEquals(4, relationships.size());
        Relationship calls = relationships.stream().filter(relationship -> relationship.type().equals("CALLS"))
                .findFirst().orElseThrow();
        assertEquals(identity(RUN, "Method"), calls.sourceIdentity());
        assertEquals(identity(BETA, "Interface"), calls.targetIdentity());
        assertEquals(3, calls.weight());
        assertEquals(Map.of("callCount", "3"), calls.attributes());
        assertEquals(CALL_AT, calls.locator());
        assertEquals("Alpha#run calls Beta.", calls.description());
        assertTrue(port.relationships(graph.otherCorpusId()).isEmpty());
    }

    @Test
    void readsAreStable() {
        assertEquals(List.copyOf(port.entities(graph.corpusId())), List.copyOf(port.entities(graph.corpusId())));
        assertEquals(List.copyOf(port.relationships(graph.corpusId())),
                List.copyOf(port.relationships(graph.corpusId())));
    }

    @Test
    void anUnknownCorpusReadsAsEmptyNeverNull() {
        String unknown = "unknown-" + graph.corpusId();

        assertEmpty(port.entities(unknown));
        assertEmpty(port.relationships(unknown));
        assertEmpty(port.textUnits(unknown));
        assertEmpty(port.communities(unknown));
        assertEmpty(port.communityMemberships(unknown));
        assertTrue(port.textUnit(unknown, "tu-alpha").isEmpty());
        assertTrue(port.entity(unknown, identity(ALPHA, "Class")).isEmpty());
        assertEmpty(port.relationshipsTouching(unknown, List.of(identity(ALPHA, "Class"))));
        assertEmpty(port.similarEntities(unknown, new float[] {1f, 0f}, 3));
        assertEmpty(port.similarCommunities(unknown, new float[] {1f, 0f}, 3));
    }

    @Test
    void textUnitsAreFoundByIdWithTheirLocators() {
        TextUnit alpha = port.textUnit(graph.corpusId(), "tu-alpha").orElseThrow();
        TextUnit expected = graph.textUnits().getFirst();

        assertEquals(expected.text(), alpha.text());
        assertEquals(expected.documentName(), alpha.documentName());
        assertEquals(expected.locator(), alpha.locator());
        assertEquals(expected.attributes(), alpha.attributes());
        assertTrue(port.textUnit(graph.corpusId(), "tu-missing").isEmpty());
        assertTrue(port.textUnit(graph.otherCorpusId(), "tu-alpha").isEmpty());
        assertEquals(Set.of("tu-alpha", "tu-beta"),
                port.textUnits(graph.corpusId()).stream().map(TextUnit::id).collect(Collectors.toSet()));
    }

    @Test
    void communitiesAndMembershipsAreScopedAndKeepAttributes() {
        Community community = port.communities(graph.corpusId()).stream().findFirst().orElseThrow();

        assertEquals(1, port.communities(graph.corpusId()).size());
        assertEquals(ContractGraph.COMMUNITY_ID, community.id());
        assertEquals("Core", community.title());
        assertEquals(Map.of("contentHash", "h-1"), community.attributes());
        assertEquals(Set.copyOf(graph.memberships()), Set.copyOf(port.communityMemberships(graph.corpusId())));
        assertEmpty(port.communities(graph.otherCorpusId()));
    }

    @Test
    void entitiesAreFoundByIdentity() {
        Entity run = port.entity(graph.corpusId(), identity(RUN, "Method")).orElseThrow();

        assertEquals(graph.entity(RUN).locator(), run.locator());
        assertTrue(port.entity(graph.corpusId(), "missing::class").isEmpty());
        assertEquals(Set.of(ALPHA, BETA), names(port.entities(graph.corpusId(),
                List.of(identity(ALPHA, "Class"), identity(BETA, "Interface"), "missing::class"))));
    }

    @Test
    void relationshipsTouchingAreExactlyTheOnesWithAnEndpointAmongTheIdentities() {
        List<String> identities = List.of(identity(BETA, "Interface"));
        Set<String> expected = new HashSet<>();
        for (Relationship relationship : port.relationships(graph.corpusId())) {
            if (identities.contains(relationship.sourceIdentity()) || identities.contains(relationship.targetIdentity())) {
                expected.add(key(relationship));
            }
        }

        Set<String> touching = port.relationshipsTouching(graph.corpusId(), identities).stream()
                .map(GraphReadPortContract::key).collect(Collectors.toSet());

        assertEquals(Set.of(identity(RUN, "Method") + "|CALLS|" + identity(BETA, "Interface"),
                identity(ALPHA, "Class") + "|IMPLEMENTS|" + identity(BETA, "Interface")), touching);
        assertEquals(expected, touching);
        assertEmpty(port.relationshipsTouching(graph.corpusId(), List.of()));
    }

    @Test
    void similarityLookupsAreBoundedAndScoped() {
        List<Entity> similar = port.similarEntities(graph.corpusId(), new float[] {1f, 0f, 0f}, 2);
        List<Community> similarCommunities = port.similarCommunities(graph.corpusId(), new float[] {1f, 0f, 0f}, 1);

        assertNotNull(similar);
        assertTrue(similar.size() <= 2);
        assertTrue(names(port.entities(graph.corpusId())).containsAll(names(similar)));
        assertNotNull(similarCommunities);
        assertTrue(similarCommunities.size() <= 1);
    }

    /** The names of {@code entities}. */
    protected static Set<String> names(Collection<Entity> entities) {
        return entities.stream().map(Entity::name).collect(Collectors.toSet());
    }

    private static Entity only(Collection<Entity> entities, String name) {
        List<Entity> matching = entities.stream().filter(entity -> entity.name().equals(name)).toList();
        assertEquals(1, matching.size(), "exactly one Entity named " + name);
        return matching.getFirst();
    }

    private static String key(Relationship relationship) {
        return relationship.sourceIdentity() + "|" + relationship.type() + "|" + relationship.targetIdentity();
    }

    private static void assertEmpty(Collection<?> values) {
        assertNotNull(values);
        assertTrue(values.isEmpty(), () -> "expected empty but was " + values);
    }

    /** Loads both corpora of {@code graph} through a store's write side; for {@link GraphStorePortContract}. */
    static void persist(GraphWritePort store, ContractGraph graph) {
        store.persistTextUnits(graph.corpusId(), graph.textUnits());
        store.persistEntities(graph.corpusId(), graph.entities());
        store.persistRelationships(graph.corpusId(), graph.relationships());
        store.persistCommunities(graph.corpusId(), graph.communities());
        store.persistCommunityMemberships(graph.corpusId(), graph.memberships());
        store.persistTextUnits(graph.otherCorpusId(), graph.otherTextUnits());
        store.persistEntities(graph.otherCorpusId(), graph.otherEntities());
    }
}
