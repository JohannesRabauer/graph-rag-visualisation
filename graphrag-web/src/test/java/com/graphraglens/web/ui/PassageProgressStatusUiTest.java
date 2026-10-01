package com.graphraglens.web.ui;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Story 13.1: a {@code text-unit-extracted} progress event rewrites the
 * workflow status line while the Knowledge Graph is building. The progress
 * stream is mocked so the event arrives deterministically.
 */
class PassageProgressStatusUiTest extends UiTestSupport {

    @Test
    void textUnitExtractedEventShowsPassageProgressInTheStatusLine() {
        page.route("**/api/corpora/*/progress", (Route route) -> route.fulfill(
                new Route.FulfillOptions()
                        .setStatus(200)
                        .setContentType("text/event-stream")
                        .setBody(
                                "event: heartbeat\n"
                                        + "data: {\"type\":\"heartbeat\",\"data\":{\"message\":\"Connection established.\"}}\n\n"
                                        + "event: text-unit-extracted\n"
                                        + "data: {\"type\":\"text-unit-extracted\",\"data\":"
                                        + "{\"index\":1,\"total\":2,\"documentName\":\"engine-notes.txt\"}}\n\n")));

        page.navigate(baseUrl() + "/");
        page.locator("#demo-dataset-button").click();

        assertThat(page.locator("#workflow-status-text"))
                .hasText("Extracting passage 1 of 2 — engine-notes.txt",
                        new LocatorAssertions.HasTextOptions().setTimeout(20000));
    }

    @Test
    void relationshipRepeatedByOverlappingPassagesIsListedOnceInTheDetailPanel() {
        String entity = "event: entity-extracted\n"
                + "data: {\"type\":\"entity-extracted\",\"data\":"
                + "{\"identity\":\"ada lovelace::person\",\"name\":\"Ada Lovelace\",\"type\":\"Person\"}}\n\n"
                + "event: entity-extracted\n"
                + "data: {\"type\":\"entity-extracted\",\"data\":"
                + "{\"identity\":\"analytical engine::product\",\"name\":\"Analytical Engine\",\"type\":\"Product\"}}\n\n";
        String relationship = "event: relationship-extracted\n"
                + "data: {\"type\":\"relationship-extracted\",\"data\":"
                + "{\"sourceIdentity\":\"ada lovelace::person\",\"source\":\"Ada Lovelace\","
                + "\"targetIdentity\":\"analytical engine::product\",\"target\":\"Analytical Engine\","
                + "\"type\":\"wrote_about\"}}\n\n";
        page.route("**/api/corpora/*/progress", (Route route) -> route.fulfill(
                new Route.FulfillOptions()
                        .setStatus(200)
                        .setContentType("text/event-stream")
                        .setBody(entity + relationship + entity + relationship)));

        page.navigate(baseUrl() + "/");
        page.locator("#demo-dataset-button").click();
        page.waitForFunction("() => window.GraphCanvas && window.GraphCanvas.simulateTap('ada lovelace::person')",
                null, new Page.WaitForFunctionOptions().setTimeout(20000));

        assertThat(page.locator("#entity-detail-relationships li")).hasCount(1);
        assertThat(page.locator("#entity-detail-relationships li")).hasText("→ wrote_about → Analytical Engine");
    }
}
