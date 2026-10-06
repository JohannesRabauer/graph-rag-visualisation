package dev.rabauer.graphrag.testkit;

import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.port.GraphReadPort;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.usecase.SourceRemoval;
import dev.rabauer.graphrag.core.usecase.UpdateSources;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.rabauer.graphrag.testkit.ContractGraph.ALPHA;
import static dev.rabauer.graphrag.testkit.ContractGraph.BETA;
import static dev.rabauer.graphrag.testkit.ContractGraph.RUN;
import static dev.rabauer.graphrag.testkit.ContractGraph.identity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract of a full {@link GraphStorePort}: everything of
 * {@link GraphReadPortContract}, with the {@link ContractGraph} loaded through
 * the port's own write side, plus the write rules the use cases rely on.
 * Extend it and return a fresh (or freshly scoped) store from
 * {@link #newStore()}.
 */
public abstract class GraphStorePortContract extends GraphReadPortContract {

    /** The store under test. */
    protected GraphStorePort store;

    /** A store to run one test against; corpus ids are unique per test. */
    protected abstract GraphStorePort newStore();

    @Override
    protected final GraphReadPort givenGraph(ContractGraph graph) {
        store = newStore();
        persist(store, graph);
        return store;
    }

    @Test
    void persistingAnEntityAgainReplacesItInsteadOfDuplicating() {
        Entity alpha = graph.entity(ALPHA);
        store.persistEntities(graph.corpusId(), List.of(new Entity(alpha.name(), alpha.type(), "Changed.",
                alpha.sourceTextUnitIds(), Map.of("kind", "class"), alpha.locator())));

        List<Entity> alphas = store.entities(graph.corpusId()).stream()
                .filter(entity -> entity.name().equals(ALPHA)).toList();
        assertEquals(1, alphas.size());
        assertEquals("Changed.", alphas.getFirst().description());
        assertEquals(Map.of("kind", "class"), alphas.getFirst().attributes());
        assertEquals(4, store.entities(graph.corpusId()).size());
    }

    @Test
    void persistingARelationshipAgainReplacesItsWeight() {
        store.persistRelationships(graph.corpusId(), List.of(new Relationship(RUN, "Method", "CALLS", BETA,
                "Interface", "Alpha#run calls Beta.", List.of("tu-alpha"), 5, Map.of("callCount", "5"),
                ContractGraph.CALL_AT)));

        List<Relationship> calls = store.relationships(graph.corpusId()).stream()
                .filter(relationship -> relationship.type().equals("CALLS")).toList();
        assertEquals(1, calls.size());
        assertEquals(5, calls.getFirst().weight());
        assertEquals(4, store.relationships(graph.corpusId()).size());
    }

    @Test
    void detectCommunitiesPutsEveryEntityOfTheCorpusInExactlyOneGroup() {
        List<List<String>> groups = store.detectCommunities(graph.corpusId());

        Set<String> seen = new HashSet<>();
        for (List<String> group : groups) {
            for (String identity : group) {
                assertTrue(seen.add(identity), "identity in two groups: " + identity);
            }
        }
        assertEquals(Set.of(identity(ALPHA, "Class"), identity(RUN, "Method"), identity(BETA, "Interface"),
                identity(ContractGraph.GAMMA, "Class")), seen);
        assertTrue(store.detectCommunities("unknown-" + graph.corpusId()).isEmpty());
    }

    @Test
    void embeddingsMakeEntitiesAndCommunitiesFindableBySimilarityWhenSupported() {
        store.persistEntityEmbeddings(graph.corpusId(), Map.of(
                identity(ALPHA, "Class"), new float[] {1f, 0f, 0f},
                identity(BETA, "Interface"), new float[] {0f, 1f, 0f}));
        store.persistCommunityEmbeddings(graph.corpusId(), Map.of(ContractGraph.COMMUNITY_ID, new float[] {1f, 0f, 0f}));

        List<Entity> similar = store.similarEntities(graph.corpusId(), new float[] {0.9f, 0.1f, 0f}, 1);

        assertTrue(similar.size() <= 1);
        if (supportsSimilarity()) {
            assertEquals(ALPHA, similar.getFirst().name());
            assertEquals(ContractGraph.COMMUNITY_ID,
                    store.similarCommunities(graph.corpusId(), new float[] {1f, 0f, 0f}, 1).getFirst().id());
            assertTrue(store.similarEntities(graph.otherCorpusId(), new float[] {1f, 0f, 0f}, 3).isEmpty());
        }
    }

    @Test
    void deletingAnEntityCascadesToItsRelationshipsAndMemberships() {
        if (!supportsDeletion()) {
            return;
        }
        store.deleteEntities(graph.corpusId(), List.of(identity(BETA, "Interface")));

        assertEquals(Set.of(ALPHA, RUN, ContractGraph.GAMMA), names(store.entities(graph.corpusId())));
        assertTrue(store.relationships(graph.corpusId()).stream().noneMatch(relationship ->
                relationship.targetIdentity().equals(identity(BETA, "Interface"))));
        assertEquals(2, store.relationships(graph.corpusId()).size());
        assertTrue(store.communityMemberships(graph.corpusId()).stream()
                .noneMatch(membership -> membership.entityIdentity().equals(identity(BETA, "Interface"))));
        assertEquals(1, store.entities(graph.otherCorpusId()).size());
    }

    @Test
    void deletesTextUnitsRelationshipsAndCommunitiesByKey() {
        if (!supportsDeletion()) {
            return;
        }
        store.deleteTextUnits(graph.corpusId(), List.of("tu-beta", "tu-unknown"));
        store.deleteRelationships(graph.corpusId(), List.of(new Relationship(ContractGraph.GAMMA, "Class", "USES",
                ALPHA, "Class")));
        store.deleteCommunities(graph.corpusId());

        assertTrue(store.textUnit(graph.corpusId(), "tu-beta").isEmpty());
        assertTrue(store.textUnit(graph.corpusId(), "tu-alpha").isPresent());
        assertEquals(3, store.relationships(graph.corpusId()).size());
        assertTrue(store.relationships(graph.corpusId()).stream()
                .noneMatch(relationship -> relationship.type().equals("USES")));
        assertTrue(store.communities(graph.corpusId()).isEmpty());
        assertTrue(store.communityMemberships(graph.corpusId()).isEmpty());
        assertEquals(1, store.textUnits(graph.otherCorpusId()).size());
    }

    @Test
    void removingASourceDeletesItsElementsAndReportsStaleCommunities() {
        if (!supportsDeletion()) {
            return;
        }
        SourceRemoval removal = new UpdateSources(store).removeBySource(graph.corpusId(), ContractGraph.BETA_AT.path());

        assertEquals(List.of(identity(BETA, "Interface")), removal.removedEntities());
        assertEquals(1, removal.removedTextUnits());
        assertEquals(2, removal.removedRelationships());
        assertEquals(List.of(ContractGraph.COMMUNITY_ID), removal.staleCommunityIds());
        assertEquals(Set.of(ALPHA, RUN, ContractGraph.GAMMA), names(store.entities(graph.corpusId())));
        assertTrue(store.textUnit(graph.corpusId(), "tu-beta").isEmpty());
    }

    /**
     * Whether the store implements the delete methods (then they must be
     * correct). The default is {@code true}.
     */
    protected boolean supportsDeletion() {
        return true;
    }

    /**
     * Whether the store answers similarity lookups (then they must be
     * correct). The default is {@code true}; return {@code false} for a store
     * that always answers empty.
     */
    protected boolean supportsSimilarity() {
        return true;
    }
}
