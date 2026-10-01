package com.graphraglens.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Story 14.2: a Community's short title names it in the legend chip, on its
 * hull and in the detail panel, while the detail panel still shows the full
 * summary; a Community without a title keeps today's summary-derived label.
 *
 * <p>Communities are added through {@code GraphCanvas.addCommunity} (the same
 * API the SSE and graph-endpoint paths drive) after the demo dataset has
 * initialised the canvas, so the titles under test are fixed and known.
 */
class CommunityTitleUiTest extends UiTestSupport {

    private static final String OPEN_CLASS_PATTERN = ".*\\bis-open\\b.*";
    // The first demo load in a cold JVM can exceed UiTestSupport's 20 s Ready wait.
    private static final double READY_TIMEOUT_MS = 60000;

    private void loadDemoDatasetAndWaitReadyColdStartTolerant() {
        page.navigate(baseUrl() + "/");
        page.locator("#demo-dataset-button").click();
        assertThat(page.locator("#workflow-status-text"))
                .containsText("Ready", new LocatorAssertions.ContainsTextOptions().setTimeout(READY_TIMEOUT_MS));
    }
    private static final String TITLED = "titled-community";
    private static final String UNTITLED = "untitled-community";
    private static final String TITLE = "Baker Street Detectives";
    private static final String FULL_SUMMARY = "Sherlock Holmes and Dr. Watson investigate cases together from "
            + "their rooms at Baker Street, with Watson recording each adventure.";
    private static final String UNTITLED_SUMMARY = "A loose cluster of minor characters who appear only briefly "
            + "in the stories.";

    @Test
    void legendChipHullLabelAndDetailPanelUseTheTitleAndFallBackToTheSummaryLabelWithoutOne() {
        loadDemoDatasetAndWaitReadyColdStartTolerant();

        page.evaluate("([titled, untitled, title, summary, untitledSummary]) => {"
                        + "  const gc = window.GraphCanvas;"
                        + "  gc.addEntity('titled-a::concept', 'Titled A', 'concept');"
                        + "  gc.addEntity('titled-b::concept', 'Titled B', 'concept');"
                        + "  gc.addEntity('untitled-a::concept', 'Untitled A', 'concept');"
                        + "  gc.addCommunity(titled, summary, ['titled-a::concept', 'titled-b::concept'], title);"
                        + "  gc.addCommunity(untitled, untitledSummary, ['untitled-a::concept'], '');"
                        + "}",
                List.of(TITLED, UNTITLED, TITLE, FULL_SUMMARY, UNTITLED_SUMMARY));

        // Same truncation as graph-canvas.js's legendLabel (42 chars incl. the ellipsis).
        String fallbackLabel = UNTITLED_SUMMARY.substring(0, 41).trim() + "…";

        assertThat(page.locator(".graph-legend-item[data-community-id='" + TITLED + "'] .graph-legend-name"))
                .hasText(TITLE);
        assertThat(page.locator(".graph-legend-item[data-community-id='" + UNTITLED + "'] .graph-legend-name"))
                .hasText(fallbackLabel);
        org.assertj.core.api.Assertions.assertThat(
                (String) page.evaluate("id => window.GraphCanvas.communityHullLabel(id)", TITLED)).isEqualTo(TITLE);
        org.assertj.core.api.Assertions.assertThat(
                (String) page.evaluate("id => window.GraphCanvas.communityHullLabel(id)", UNTITLED))
                .isEqualTo(fallbackLabel);

        boolean fired = (boolean) page.evaluate("id => window.GraphCanvas.simulateTap(id)", "community::" + TITLED);
        org.assertj.core.api.Assertions.assertThat(fired).isTrue();

        Locator panel = page.locator("#entity-detail-panel");
        assertThat(panel).hasClass(java.util.regex.Pattern.compile(OPEN_CLASS_PATTERN));
        assertThat(page.locator("#entity-detail-eyebrow")).hasText("Community");
        assertThat(page.locator("#entity-detail-name")).hasText(TITLE);
        assertThat(page.locator("#entity-detail-description")).hasText(FULL_SUMMARY);
    }

    @Test
    void demoDatasetLegendAndHullsShowTheGraphEndpointTitlesLiveAndAfterReload() {
        loadDemoDatasetAndWaitReadyColdStartTolerant();

        // Live path: the Communities arrived via the `community-detected` SSE events.
        Map<String, String> titles = graphEndpointTitles();
        assertLegendAndHullsShow(titles);

        // Restore path: a reload rebuilds the canvas from GET /api/corpora/{id}/graph.
        page.reload();
        assertThat(page.locator("#workflow-status-text"))
                .containsText("Ready", new LocatorAssertions.ContainsTextOptions().setTimeout(READY_TIMEOUT_MS));
        assertLegendAndHullsShow(titles);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> graphEndpointTitles() {
        List<Map<String, Object>> communities = (List<Map<String, Object>>) page.evaluate("async () => {"
                + "  const corpora = (await (await fetch('/api/corpora')).json()).corpora;"
                + "  const graph = await (await fetch('/api/corpora/' + corpora[0].id + '/graph')).json();"
                + "  return graph.communities.map(c => ({ communityId: c.communityId, title: c.title,"
                + "    summary: c.summary }));"
                + "}");
        org.assertj.core.api.Assertions.assertThat(communities).isNotEmpty();
        Map<String, String> titles = new LinkedHashMap<>();
        for (Map<String, Object> community : communities) {
            String title = (String) community.get("title");
            String summary = (String) community.get("summary");
            org.assertj.core.api.Assertions.assertThat(title).isNotBlank();
            // Guards against the test passing on the summary-derived fallback label.
            org.assertj.core.api.Assertions.assertThat(summary).doesNotStartWith(title);
            titles.put((String) community.get("communityId"), title);
        }
        return titles;
    }

    private void assertLegendAndHullsShow(Map<String, String> titles) {
        titles.forEach((communityId, title) -> {
            assertThat(page.locator(".graph-legend-item[data-community-id='" + communityId + "'] .graph-legend-name"))
                    .hasText(title);
            org.assertj.core.api.Assertions.assertThat(
                    (String) page.evaluate("id => window.GraphCanvas.communityHullLabel(id)", communityId))
                    .isEqualTo(title);
        });
    }
}
