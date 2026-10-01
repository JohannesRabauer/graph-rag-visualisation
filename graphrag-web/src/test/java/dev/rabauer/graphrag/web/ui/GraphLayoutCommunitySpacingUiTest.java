package dev.rabauer.graphrag.web.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for GitHub #34 (spec-11-5): the {@code cose} layout's
 * spacing-relevant parameters (`nestingFactor`, `componentSpacing`) were left
 * at Cytoscape's generic defaults, which are not tuned for this app's typical
 * graph shape (many small Communities expressed as compound/parent nodes,
 * plus isolated/disconnected entities) — so nodes and whole Communities could
 * render at wildly disproportionate distances from the rest of the graph.
 *
 * <p>Investigation (see the spec) confirmed the compound-node
 * community-grouping mechanism itself (`addCommunity`/`addEntity` reparenting)
 * is already correct, and this is purely a layout-parameter-tuning gap. This
 * test asserts against the actual rendered layout (via
 * {@code window.GraphCanvas.layoutSpacingMetrics()}, a small test-support hook
 * added alongside `graph-canvas.js`'s existing ones), not just reasoning
 * about `cose`'s documented behavior — this codebase's own precedent
 * (Stories 11.1-11.3) shows static reasoning about layout/CSS can miss or
 * wrongly assume real behavior.
 *
 * <p>The demo dataset's own corpus (loaded only to get {@code GraphCanvas}
 * initialized) is deliberately tiny (a handful of Entities across four
 * Communities, most with 1-2 members) — too small and edge-sparse for a
 * per-Community "cohesion" signal to rise above `cose`'s own inherent
 * simulated-annealing noise. This test instead builds its own synthetic,
 * "typical-sized" corpus directly through {@code GraphCanvas}'s public
 * `addEntity`/`addRelationship`/`addCommunity` API (the exact same API the
 * real corpus-ingestion path drives) on top of it: two densely-connected,
 * multi-member Communities plus one genuinely isolated Entity — matching the
 * three rows of the spec's own I/O matrix.
 *
 * <p>{@code cose} is a stochastic simulated-annealing layout (random
 * per-iteration perturbation, cooled over a fixed iteration budget), so any
 * single run's rendered positions are one sample of a distribution, not a
 * fixed point. This test therefore re-runs the layout several times (via
 * {@code runLayoutSynchronouslyForTest}, test-support only, bypassing the
 * real app's debounced/animated layout path) on the same already-built graph
 * and asserts on the *median* outcome, exactly like this codebase's own
 * precedent for other flaky-under-automation `cose` behavior
 * ({@code simulateTap}'s own comment) — a single unlucky sample failing the
 * build would not, by itself, mean the tuning regressed.
 *
 * <p>Every bound below is expressed as a multiple of the graph's own median
 * edge length — its "typical" spacing unit — rather than a fixed pixel
 * value, so the test stays meaningful regardless of corpus size or viewport.
 *
 * <p>The community-cohesion bound is tight and reliable: it is governed by
 * direct member-to-member edge attraction (elasticity vs. repulsion), which
 * settles to a consistent equilibrium run after run regardless of
 * `nestingFactor`/`componentSpacing`. The isolated-node/outlier-spacing bound
 * is deliberately coarse: empirically sweeping `nestingFactor` from 0.05-10
 * and `componentSpacing` from 1-500 on this exact corpus (recorded while
 * tuning this fix) showed `cose`'s own per-iteration simulated-annealing
 * randomness — not either of these two options — dominates how far a
 * disconnected/singleton Community's node lands from its nearest neighbor;
 * neither option reliably keeps that metric under roughly 40x the median edge
 * length even at extreme settings. This bound therefore isn't a tight
 * statistical proof that the tuning halved the typical gap (it does, on
 * average, per that same sweep) — it's a regression guard against the
 * mechanism breaking entirely (e.g. `componentSpacing` regressing to
 * hundreds, or the compound-parenting itself breaking so Entities are no
 * longer grouped at all), which this bound catches reliably.
 */
class GraphLayoutCommunitySpacingUiTest extends UiTestSupport {

    private static final int LAYOUT_SAMPLES = 11;
    private static final String COMMUNITY_A = "synthetic-community-a";
    private static final String COMMUNITY_B = "synthetic-community-b";
    private static final String ISOLATED_ENTITY = "synthetic-isolated::concept";

    @Test
    void communityMembersAndIsolatedNodesStayWithinBoundedSpacingOfTheRestOfTheGraph() {
        loadDemoDatasetAndWaitReady();
        buildSyntheticCorpus();

        // Every `addEntity`/`addRelationship`/`addCommunity` call above
        // queued its own (debounced, animated) `cose` layout; wait for the
        // layout's own metrics to report both synthetic Communities before
        // sampling.
        page.waitForFunction(
                "() => {"
                        + "  const m = window.GraphCanvas.layoutSpacingMetrics();"
                        + "  return !!(m && m.medianEdgeLength"
                        + "    && m.communities['" + COMMUNITY_A + "'] && m.communities['" + COMMUNITY_B + "']);"
                        + "}");
        page.waitForTimeout(500);

        List<Double> communityDiagonalRatios = new ArrayList<>();
        List<Double> nearestNeighborGapRatios = new ArrayList<>();

        for (int sample = 0; sample < LAYOUT_SAMPLES; sample++) {
            // Re-run the same tuned layout synchronously (no animation to
            // wait out) so each sample is an independent draw of `cose`'s
            // own randomness, on the same graph.
            Boolean ran = (Boolean) page.evaluate("() => window.GraphCanvas.runLayoutSynchronouslyForTest()");
            assertThat(ran).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> metrics =
                    (Map<String, Object>) page.evaluate("() => window.GraphCanvas.layoutSpacingMetrics()");
            assertThat(metrics).isNotNull();

            double medianEdgeLength = ((Number) metrics.get("medianEdgeLength")).doubleValue();
            assertThat(medianEdgeLength).isGreaterThan(0.0);

            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> communities =
                    (Map<String, Map<String, Object>>) metrics.get("communities");
            assertThat(communities).containsKeys(COMMUNITY_A, COMMUNITY_B);

            // Community cohesion: each synthetic Community's own members
            // (densely chained by direct Relationships) shouldn't render
            // spread across a diagonal many multiples of a typical edge
            // length — that would mean the Community rendered as a
            // scattered cloud rather than a visually coherent cluster.
            double communityADiagonal = ((Number) communities.get(COMMUNITY_A).get("diagonal")).doubleValue();
            double communityBDiagonal = ((Number) communities.get(COMMUNITY_B).get("diagonal")).doubleValue();
            communityDiagonalRatios.add(communityADiagonal / medianEdgeLength);
            communityDiagonalRatios.add(communityBDiagonal / medianEdgeLength);

            double maxNearestNeighborGap = ((Number) metrics.get("maxNearestNeighborGap")).doubleValue();
            nearestNeighborGapRatios.add(maxNearestNeighborGap / medianEdgeLength);
        }

        double medianCommunityDiagonalRatio = median(communityDiagonalRatios);
        double medianNearestNeighborGapRatio = median(nearestNeighborGapRatios);

        // Community cohesion is reliably tight run-to-run (governed by
        // direct member-to-member edge attraction, not by isolated-node
        // repulsion drift), so this bound stays strict.
        assertThat(medianCommunityDiagonalRatio)
                .describedAs("median, across %d layout samples, of each synthetic Community's own member "
                        + "bounding-box diagonal as a multiple of the graph's median edge length",
                        LAYOUT_SAMPLES)
                .isLessThanOrEqualTo(8.0);

        // Outlier spacing: across repeated samples, the isolated Entity (no
        // Relationships, its own singleton Community) should not sit
        // absurdly far from its own closest neighbor — that gap is exactly
        // what "drifts to an outlier distance" (GitHub #34) looks like in
        // rendered positions. See the class Javadoc for why this bound is
        // coarse rather than tight.
        assertThat(medianNearestNeighborGapRatio)
                .describedAs("median, across %d layout samples, of the largest nearest-neighbor gap across all "
                        + "rendered nodes as a multiple of the graph's median edge length",
                        LAYOUT_SAMPLES)
                .isLessThanOrEqualTo(50.0);
    }

    /**
     * Adds a "typical-sized" corpus on top of whatever the demo dataset
     * already rendered, directly through {@code GraphCanvas}'s own public
     * API (the same calls the real corpus-ingestion/SSE path drives) — two
     * 6-member Communities, each a densely-connected chain (5 Relationships
     * apiece), plus one genuinely isolated Entity with no Relationships at
     * all, in its own singleton Community.
     */
    private void buildSyntheticCorpus() {
        page.evaluate(
                "([communityA, communityB, isolatedEntity]) => {"
                        + "  const gc = window.GraphCanvas;"
                        // Re-init wipes the demo dataset's own (tiny, noisy)
                        // Entities/Communities from `cy` first, so this
                        // test's spacing metrics measure only the synthetic
                        // corpus built below, not a mix of both graphs
                        // sharing one canvas.
                        + "  gc.init({});"
                        + "  const membersOf = (prefix) => Array.from({ length: 6 }, (_, i) => prefix + '-' + i + '::concept');"
                        + "  const chain = (members) => {"
                        + "    members.forEach((id) => gc.addEntity(id, id, 'concept'));"
                        + "    for (let i = 0; i < members.length - 1; i++) {"
                        + "      gc.addRelationship(members[i], members[i], members[i + 1], members[i + 1], 'related_to');"
                        + "    }"
                        + "    return members;"
                        + "  };"
                        + "  const membersA = chain(membersOf(communityA));"
                        + "  const membersB = chain(membersOf(communityB));"
                        + "  gc.addCommunity(communityA, communityA, membersA);"
                        + "  gc.addCommunity(communityB, communityB, membersB);"
                        + "  gc.addEntity(isolatedEntity, isolatedEntity, 'concept');"
                        + "  gc.addCommunity('synthetic-community-isolated', 'synthetic-community-isolated', [isolatedEntity]);"
                        + "}",
                List.of(COMMUNITY_A, COMMUNITY_B, ISOLATED_ENTITY));
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 0) {
            return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0;
        }
        return sorted.get(middle);
    }
}
