package com.graphraglens.web.ui;

import com.microsoft.playwright.Route;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Playwright UI tests for the bugfix in
 * spec-10-2-progress-stream-false-disconnected-banner.md (GitHub #21).
 *
 * <p>{@code CorpusProgressService.register()} never calls
 * {@code emitter.complete()} after a terminal event, so every real progress
 * stream eventually times out after 30s and the browser's
 * {@code EventSource} fires {@code onerror} — even once the corpus has
 * already reached {@code READY}. Rather than wait a real 30 seconds, these
 * tests mock {@code **}{@code /api/corpora/*}{@code /progress} to return a
 * synthetic SSE body that ends the HTTP response immediately, reproducing
 * the same transport-level disconnect deterministically.
 */
class ProgressStreamDisconnectedBannerUiTest extends UiTestSupport {

    private static final String DISCONNECT_MESSAGE =
            "The progress stream disconnected. You can reconnect it or start over with a new corpus.";

    @Test
    void streamClosingAfterReadyShowsNoDisconnectedBanner() {
        page.route("**/api/corpora/*/progress", (Route route) -> route.fulfill(
                new Route.FulfillOptions()
                        .setStatus(200)
                        .setContentType("text/event-stream")
                        .setBody(
                                "event: heartbeat\n"
                                        + "data: {\"type\":\"heartbeat\",\"data\":{\"message\":\"Connection established.\"}}\n\n"
                                        + "event: ingestion-complete\n"
                                        + "data: {\"type\":\"ingestion-complete\",\"data\":{}}\n\n")));

        page.navigate(baseUrl() + "/");
        page.locator("#demo-dataset-button").click();

        assertThat(page.locator("#workflow-status-text"))
                .containsText("Ready", new LocatorAssertions.ContainsTextOptions().setTimeout(20000));
        assertThat(page.locator("#error-banner"))
                .isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(20000));
    }

    @Test
    void streamClosingWhileBuildingStillShowsTheDisconnectedBanner() {
        page.route("**/api/corpora/*/progress", (Route route) -> route.fulfill(
                new Route.FulfillOptions()
                        .setStatus(200)
                        .setContentType("text/event-stream")
                        .setBody(
                                "event: heartbeat\n"
                                        + "data: {\"type\":\"heartbeat\",\"data\":{\"message\":\"Connection established.\"}}\n\n")));

        page.navigate(baseUrl() + "/");
        page.locator("#demo-dataset-button").click();

        assertThat(page.locator("#error-banner"))
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        assertThat(page.locator("#error-banner"))
                .containsText(DISCONNECT_MESSAGE, new LocatorAssertions.ContainsTextOptions().setTimeout(20000));
        assertThat(page.locator("#workflow-status-text"))
                .not().containsText("Ready", new LocatorAssertions.ContainsTextOptions().setTimeout(20000));
    }
}
