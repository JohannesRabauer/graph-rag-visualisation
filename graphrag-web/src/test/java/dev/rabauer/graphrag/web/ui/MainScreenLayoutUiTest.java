package dev.rabauer.graphrag.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-browser coverage for Story 11.1 (GitHub #30): the main screen was
 * capped at {@code .app-frame { max-width: 1180px; }}, leaving large unused
 * margins on wide monitors, and the Cytoscape canvas never re-fit itself
 * after its container's box changed (no {@code ResizeObserver} tied to size
 * changes anywhere), so it could go stale even once the frame widened.
 */
class MainScreenLayoutUiTest extends UiTestSupport {

    @Test
    void appFrameFillsAGenerousWidthOnAWideViewportButStaysCapped() {
        page.setViewportSize(2000, 1100);
        loadDemoDatasetAndWaitReady();

        Locator appFrame = page.locator(".app-frame");
        BoundingBox box = appFrame.boundingBox();
        assertThat(box).isNotNull();

        // Previously capped at 1180px, leaving ~800px of unused margin at
        // this viewport width — now should grow well past that old cap...
        assertThat(box.width).isGreaterThan(1180);
        // ...but still be bounded by the new, generous-but-sane cap (spec's
        // 1900px), never truly unbounded on an ultrawide monitor.
        assertThat(box.width).isLessThanOrEqualTo(1900);
    }

    @Test
    void cytoscapeCanvasResizesAfterAPostLoadViewportChange() {
        page.setViewportSize(1400, 900);
        loadDemoDatasetAndWaitReady();

        @SuppressWarnings("unchecked")
        Map<String, Object> before =
                (Map<String, Object>) page.evaluate("() => window.GraphCanvas.dimensions()");
        assertThat(before).isNotNull();
        double widthBefore = ((Number) before.get("width")).doubleValue();

        @SuppressWarnings("unchecked")
        Map<String, Object> viewBefore =
                (Map<String, Object>) page.evaluate("() => window.GraphCanvas.viewState()");
        assertThat(viewBefore).isNotNull();

        page.setViewportSize(800, 700);

        // The resize-triggered `cy.resize()` runs un-debounced on every
        // observed change, so `page.waitForFunction` (Playwright's own
        // polling primitive) should see the new width shortly after the
        // viewport change with no other user action required.
        page.waitForFunction(
                "expected => { const d = window.GraphCanvas.dimensions(); return d && d.width !== expected; }",
                widthBefore);

        @SuppressWarnings("unchecked")
        Map<String, Object> after =
                (Map<String, Object>) page.evaluate("() => window.GraphCanvas.dimensions()");
        assertThat(after).isNotNull();
        double widthAfter = ((Number) after.get("width")).doubleValue();
        assertThat(widthAfter).isNotEqualTo(widthBefore);

        // `cy.resize()` alone only proves the cached width/height changed —
        // it says nothing about the debounced `cy.fit()` (RESIZE_FIT_DEBOUNCE_MS
        // = 120ms) that is supposed to re-center/re-zoom the graph afterwards.
        // Wait past that debounce window, then assert the viewport's actual
        // zoom/pan changed as a result, so deleting the `cy.fit()` call would
        // fail this test.
        page.waitForTimeout(300);

        @SuppressWarnings("unchecked")
        Map<String, Object> viewAfter =
                (Map<String, Object>) page.evaluate("() => window.GraphCanvas.viewState()");
        assertThat(viewAfter).isNotNull();
        assertThat(viewAfter).isNotEqualTo(viewBefore);
    }
}
