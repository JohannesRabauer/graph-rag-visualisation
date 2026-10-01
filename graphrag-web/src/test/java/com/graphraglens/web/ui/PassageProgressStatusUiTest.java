package com.graphraglens.web.ui;

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
}
