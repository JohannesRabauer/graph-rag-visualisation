package dev.rabauer.graphrag.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-browser coverage for Story 11.6 (GitHub #35): visible zoom in/out and
 * fit-to-view controls on the graph canvas, docked as a third sibling inside
 * {@code #canvas-top-right-stack} (Story 11.7's reserved slot for exactly
 * this) rather than a one-off absolutely-positioned corner element.
 */
class ZoomControlsUiTest extends UiTestSupport {

    @Test
    void zoomControlsAreHiddenUntilACorpusIsReady() {
        page.navigate(baseUrl() + "/");

        assertThat(page.locator("#canvas-zoom-controls")).isHidden();

        page.locator("#demo-dataset-button").click();
        assertThat(page.locator("#workflow-status-text"))
                .containsText("Ready", new LocatorAssertions.ContainsTextOptions().setTimeout(20000));

        assertThat(page.locator("#canvas-zoom-controls")).isVisible();
        assertThat(page.locator("#canvas-zoom-in-button")).isVisible();
        assertThat(page.locator("#canvas-zoom-out-button")).isVisible();
        assertThat(page.locator("#canvas-zoom-fit-button")).isVisible();
    }

    @Test
    void clickingZoomInIncreasesZoomAndZoomOutDecreasesIt() {
        loadDemoDatasetAndWaitReady();
        // A final `queueLayout()`-triggered fit (from the last entity/
        // relationship/community SSE event) can still be pending/animating
        // just after "Ready" fires — settle past it before reading the
        // "before" zoom, so it doesn't race the button-driven zoom below
        // and silently overwrite it mid-animation.
        settleZoom();

        double initialZoom = currentZoom();

        double afterZoomIn = clickAndSettle("#canvas-zoom-in-button", initialZoom);
        assertThat(afterZoomIn).isGreaterThan(initialZoom);

        double afterZoomOut = clickAndSettle("#canvas-zoom-out-button", afterZoomIn);
        assertThat(afterZoomOut).isLessThan(afterZoomIn);
    }

    @Test
    void zoomInThenZoomOutReturnsToApproximatelyTheOriginalZoomLevel() {
        // The code comment on zoomIn/zoomOut describes a symmetric x1.25 /
        // /1.25 step so one zoom-in followed by one zoom-out returns to the
        // original zoom level — asserted directly here as a round trip.
        loadDemoDatasetAndWaitReady();
        settleZoom();

        double initialZoom = currentZoom();

        double afterZoomIn = clickAndSettle("#canvas-zoom-in-button", initialZoom);
        double afterRoundTrip = clickAndSettle("#canvas-zoom-out-button", afterZoomIn);

        assertThat(afterRoundTrip).isCloseTo(initialZoom, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void clickingFitToViewResetsTheZoom() {
        loadDemoDatasetAndWaitReady();
        settleZoom();

        double initialZoom = currentZoom();

        double afterFirstZoomIn = clickAndSettle("#canvas-zoom-in-button", initialZoom);
        double zoomedInZoom = clickAndSettle("#canvas-zoom-in-button", afterFirstZoomIn);
        assertThat(zoomedInZoom).isGreaterThan(initialZoom);

        double afterFit = clickAndSettle("#canvas-zoom-fit-button", zoomedInZoom);
        assertThat(afterFit).isNotEqualTo(zoomedInZoom);

        // Strengthen the check beyond "changed": it must actually converge to
        // the graph's real fitted extent, not merely some other value. A
        // freshly, independently-computed `cy.fit()` baseline (via the
        // `fitZoomForTest` test-support hook) restores the exact same
        // viewport `fitToView()` itself targets, so the two should match (or
        // be very close).
        Object fitBaselineRaw = page.evaluate("() => window.GraphCanvas.fitZoomForTest()");
        assertThat(fitBaselineRaw).isNotNull();
        double fitBaseline = ((Number) fitBaselineRaw).doubleValue();
        assertThat(afterFit).isCloseTo(fitBaseline, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void keyboardOnlyActivationOfAZoomButtonHasTheSameEffectAsAClick() {
        // Tab-focus the zoom-in button (never clicked) and activate it with
        // Enter — matching the keyboard test pattern established for the
        // settings button in CanvasSettingsPopoverUiTest.
        loadDemoDatasetAndWaitReady();
        settleZoom();

        double initialZoom = currentZoom();

        page.locator("#canvas-zoom-in-button").focus();
        assertThat(page.locator("#canvas-zoom-in-button")).isFocused();
        page.keyboard().press("Enter");
        waitForZoomToChangeFrom(initialZoom);
        settleZoom();
        double afterEnter = currentZoom();
        assertThat(afterEnter).isGreaterThan(initialZoom);

        // Space must activate it too, the same as a real click would.
        page.locator("#canvas-zoom-out-button").focus();
        assertThat(page.locator("#canvas-zoom-out-button")).isFocused();
        page.keyboard().press("Space");
        waitForZoomToChangeFrom(afterEnter);
        settleZoom();
        double afterSpace = currentZoom();
        assertThat(afterSpace).isLessThan(afterEnter);
    }

    @Test
    void mouseWheelZoomStillWorks() {
        loadDemoDatasetAndWaitReady();
        settleZoom();

        double initialZoom = currentZoom();

        Locator canvas = page.locator("#graph-canvas");
        canvas.hover();
        page.mouse().wheel(0, -200);
        waitForZoomToChangeFrom(initialZoom);

        double afterWheel = currentZoom();
        assertThat(afterWheel).isNotEqualTo(initialZoom);
    }

    @Test
    void zoomControlsClusterDoesNotOverlapEntitySearchOrSettingsButton() {
        loadDemoDatasetAndWaitReady();

        assertControlsDoNotOverlap(page.locator("#canvas-zoom-controls"), page.locator("#entity-search"));
        assertControlsDoNotOverlap(page.locator("#canvas-zoom-controls"), page.locator("#canvas-settings-toggle"));
    }

    @Test
    void zoomControlsClusterDoesNotOverlapAtNarrowViewportWidth() {
        page.setViewportSize(600, 800);
        loadDemoDatasetAndWaitReady();

        assertControlsDoNotOverlap(page.locator("#canvas-zoom-controls"), page.locator("#entity-search"));
        assertControlsDoNotOverlap(page.locator("#canvas-zoom-controls"), page.locator("#canvas-settings-toggle"));
    }

    // Clicks a zoom button and waits for its animation to both start
    // (zoom actually changed from `previousZoom`) and finish (settled),
    // so the returned value is the animation's real target and a
    // subsequent click is never a no-op against the in-progress guard.
    private double clickAndSettle(String selector, double previousZoom) {
        page.locator(selector).click();
        waitForZoomToChangeFrom(previousZoom);
        settleZoom();
        return currentZoom();
    }

    // Deterministic replacement for a fixed `page.waitForTimeout` after a
    // zoom-changing action: polls the observed zoom value (via
    // `window.GraphCanvas.viewState().zoom`) until it actually differs from
    // its prior value, matching the pattern MainScreenLayoutUiTest's
    // `cytoscapeCanvasResizesAfterAPostLoadViewportChange` test already uses.
    private void waitForZoomToChangeFrom(double previousZoom) {
        page.waitForFunction(
                "expected => {"
                        + "  const v = window.GraphCanvas.viewState();"
                        + "  return v && Math.abs(v.zoom - expected) > 1e-6;"
                        + "}",
                previousZoom);
    }

    // A final `queueLayout()`-triggered fit (from the last entity/
    // relationship/community SSE event, or from a zoomIn/zoomOut/fit button
    // click) can still be pending/animating. Rather than a fixed sleep, poll
    // the `isLayoutActive()` test-support hook until the debounced/animated
    // `cose` layout has actually stopped, then poll the zoom value itself
    // until two consecutive samples agree, covering a still-finishing
    // `cy.animate({ zoom })` that `isLayoutActive()` alone doesn't track.
    private void settleZoom() {
        page.waitForFunction("() => !window.GraphCanvas.isLayoutActive()");

        double previous = currentZoom();
        for (int i = 0; i < 30; i++) {
            page.waitForTimeout(100);
            double current = currentZoom();
            if (Math.abs(current - previous) < 1e-9) {
                return;
            }
            previous = current;
        }
    }

    private double currentZoom() {
        @SuppressWarnings("unchecked")
        Map<String, Object> viewState = (Map<String, Object>) page.evaluate("() => window.GraphCanvas.viewState()");
        assertThat(viewState).isNotNull();
        return ((Number) viewState.get("zoom")).doubleValue();
    }

    private void assertControlsDoNotOverlap(Locator first, Locator second) {
        assertThat(first).isVisible();
        assertThat(second).isVisible();

        com.microsoft.playwright.options.BoundingBox firstBox = first.boundingBox();
        com.microsoft.playwright.options.BoundingBox secondBox = second.boundingBox();
        assertThat(firstBox).isNotNull();
        assertThat(secondBox).isNotNull();

        boolean overlapsVertically = firstBox.y < secondBox.y + secondBox.height
                && secondBox.y < firstBox.y + firstBox.height;
        boolean overlapsHorizontally = firstBox.x < secondBox.x + secondBox.width
                && secondBox.x < firstBox.x + firstBox.width;

        assertThat(overlapsVertically && overlapsHorizontally)
                .withFailMessage("%s and %s bounding boxes overlap", firstBox, secondBox)
                .isFalse();
    }
}
