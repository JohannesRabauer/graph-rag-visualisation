package dev.rabauer.graphrag.web.ui;

import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Real-browser coverage for spec-11-7 (GitHub #36): the community-formation
 * and color-code-entity-types toggles no longer render permanently in the
 * top-right stack — they now nest inside a collapsible popover behind a
 * single small settings button, so a permanently-rendered absolute overlay
 * can never grow to compete with canvas content again.
 */
class CanvasSettingsPopoverUiTest extends UiTestSupport {

    @Test
    void settingsButtonIsVisibleWithThePopoverCollapsedByDefault() {
        loadDemoDatasetAndWaitReady();

        assertThat(page.locator("#canvas-settings-toggle")).isVisible();
        assertThat(page.locator("#canvas-settings-popover")).isHidden();
        assertThat(page.locator("#community-toggle-wrap")).isHidden();
        assertThat(page.locator("#entity-type-toggle-wrap")).isHidden();
    }

    @Test
    void clickingTheSettingsButtonOpensThePopoverRevealingBothToggles() {
        loadDemoDatasetAndWaitReady();

        page.locator("#canvas-settings-toggle").click();

        assertThat(page.locator("#canvas-settings-popover")).isVisible();
        assertThat(page.locator("#community-visualization-toggle")).isVisible();
        assertThat(page.locator("#entity-type-color-toggle")).isVisible();
        assertThat(page.locator("#canvas-settings-toggle")).hasAttribute("aria-expanded", "true");
    }

    @Test
    void clickingOutsideThePopoverClosesItAndTheButtonRemainsClickable() {
        loadDemoDatasetAndWaitReady();

        page.locator("#canvas-settings-toggle").click();
        assertThat(page.locator("#canvas-settings-popover")).isVisible();

        // A click far away from both the button and the popover (the
        // top-left corner, over the canvas tab bar/graph-stage) must close it.
        page.mouse().click(10, 10);

        assertThat(page.locator("#canvas-settings-popover")).isHidden();
        assertThat(page.locator("#canvas-settings-toggle")).isVisible();

        // Re-clickable: opening it again still works.
        page.locator("#canvas-settings-toggle").click();
        assertThat(page.locator("#canvas-settings-popover")).isVisible();
    }

    @Test
    void pressingEscapeClosesThePopoverAndTheButtonRemainsVisible() {
        loadDemoDatasetAndWaitReady();

        page.locator("#canvas-settings-toggle").click();
        assertThat(page.locator("#canvas-settings-popover")).isVisible();

        page.keyboard().press("Escape");

        assertThat(page.locator("#canvas-settings-popover")).isHidden();
        assertThat(page.locator("#canvas-settings-toggle")).isVisible();
    }

    @Test
    void theSettingsButtonIsOperableFromTheKeyboardAlone() {
        loadDemoDatasetAndWaitReady();

        // Tab-focus the button (never clicked) and activate it with the
        // keyboard — the spec requires the settings button/popover to be
        // fully keyboard-operable, not just clickable.
        page.locator("#canvas-settings-toggle").focus();
        assertThat(page.locator("#canvas-settings-toggle")).isFocused();

        page.keyboard().press("Enter");
        assertThat(page.locator("#canvas-settings-popover")).isVisible();
        assertThat(page.locator("#canvas-settings-toggle")).hasAttribute("aria-expanded", "true");

        page.keyboard().press("Escape");
        assertThat(page.locator("#canvas-settings-popover")).isHidden();

        // Space must open it too, the same as a real click would.
        page.locator("#canvas-settings-toggle").focus();
        page.keyboard().press("Space");
        assertThat(page.locator("#canvas-settings-popover")).isVisible();
        assertThat(page.locator("#canvas-settings-toggle")).hasAttribute("aria-expanded", "true");
    }

    @Test
    void bothTogglesStillFunctionOnceInsideThePopover() {
        loadDemoDatasetAndWaitReady();

        String identity = "sherlock holmes::person";
        page.locator("#canvas-settings-toggle").click();

        Locator communityToggle = page.locator("#community-visualization-toggle");
        Locator entityTypeToggle = page.locator("#entity-type-color-toggle");
        assertThat(communityToggle).isChecked();
        assertThat(entityTypeToggle).isChecked();

        // Community-formation view: unchecking must hide this entity's
        // community hull (same real-geometry check
        // ReplayCommunityHullVisibilityUiTest already uses).
        communityToggle.uncheck();
        assertThat(communityToggle).not().isChecked();
        Number hullOpacity = (Number) page.evaluate(
                "identity => {"
                        + "  const communityId = window.GraphCanvas.communityIdForEntity(identity);"
                        + "  return communityId ? window.GraphCanvas.communityHullOpacity(communityId) : null;"
                        + "}",
                identity);
        org.assertj.core.api.Assertions.assertThat(hullOpacity).isNotNull();
        org.assertj.core.api.Assertions.assertThat(hullOpacity.doubleValue()).isEqualTo(0.0);

        // Entity-type color-coding: unchecking must revert this entity's
        // node fill to the shared flat neutral style (same check
        // EntityTypeColorToggleUiTest already uses).
        String coloredFill = normalizeColor((String) page.evaluate(
                "id => window.GraphCanvas.entityNodeFillColor(id)", identity));
        entityTypeToggle.uncheck();
        assertThat(entityTypeToggle).not().isChecked();
        // Node colors animate over a 0.25s transition; entities of different
        // types only share a fill once both have settled on neutral.
        page.waitForFunction(
                "id => window.GraphCanvas.entityNodeFillColor(id)"
                        + " === window.GraphCanvas.entityNodeFillColor('king::concept')",
                identity);
        String neutralFill = normalizeColor((String) page.evaluate(
                "id => window.GraphCanvas.entityNodeFillColor(id)", identity));
        org.assertj.core.api.Assertions.assertThat(neutralFill).isNotEqualTo(coloredFill);
    }

    private static String normalizeColor(String color) {
        return color == null ? null : color.replace(" ", "");
    }
}
