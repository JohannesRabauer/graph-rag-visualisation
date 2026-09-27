package com.graphraglens.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Regression coverage for spec-11-3-fix-retrieval-trace-replay-not-rendering.md
 * (GitHub #32): the investigation flagged the intersection between Replay
 * and Story 11.1's {@code ResizeObserver}-driven canvas resize
 * ({@code watchContainerResize}, see {@code MainScreenLayoutUiTest}) as
 * untested. This drives a viewport resize (which triggers the observer and
 * the debounced {@code cy.fit()}) after an answer has already rendered, then
 * confirms Replay still opens and its step/play highlighting still works —
 * the resize must not tear down or break the scrubber/highlight state.
 */
class ReplayAfterViewportResizeUiTest extends UiTestSupport {

    @Test
    void replayStillHighlightsCorrectlyAfterAPostAnswerViewportResize() {
        page.setViewportSize(1400, 900);
        loadDemoDatasetAndWaitReady();

        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();

        Locator replayCta = page.locator(".replay-cta");
        assertThat(replayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));

        // Trigger Story 11.1's ResizeObserver/`cy.resize()`+`cy.fit()` flow
        // (per MainScreenLayoutUiTest's own pattern) before Replay is opened.
        @SuppressWarnings("unchecked")
        Map<String, Object> dimensionsBefore =
                (Map<String, Object>) page.evaluate("() => window.GraphCanvas.dimensions()");
        double widthBefore = ((Number) dimensionsBefore.get("width")).doubleValue();

        page.setViewportSize(800, 700);

        // Poll for the actual resize/fit to complete instead of sleeping a
        // fixed duration (matches MainScreenLayoutUiTest's own pattern).
        page.waitForFunction(
                "expected => { const d = window.GraphCanvas.dimensions(); return d && d.width !== expected; }",
                widthBefore);

        replayCta.click();

        Locator scrubber = page.locator("#replay-scrubber");
        assertThat(scrubber).not().isHidden();

        assertReplayHighlightsAStepOnTheCanvas();
    }
}
