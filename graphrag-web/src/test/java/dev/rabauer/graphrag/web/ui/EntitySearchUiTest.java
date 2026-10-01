package dev.rabauer.graphrag.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Real-browser coverage for Story 9.4's entity-search "jump to" control —
 * finding an Entity by name-prefix instead of panning/zooming by hand.
 */
class EntitySearchUiTest extends UiTestSupport {

    private static final String OPEN_CLASS_PATTERN = ".*\\bis-open\\b.*";

    @Test
    void typingAPrefixListsMatchesAndSelectingOneOpensItsDetailPanel() {
        loadDemoDatasetAndWaitReady();

        Locator input = page.locator("#entity-search-input");
        Locator resultsList = page.locator("#entity-search-results");

        input.fill("holm");
        assertThat(resultsList).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(5000));

        Locator firstResult = resultsList.locator(".entity-search-result").first();
        assertThat(firstResult).containsText("Holmes", new LocatorAssertions.ContainsTextOptions()
                .setIgnoreCase(true));

        firstResult.click();

        // Selecting a result behaves exactly like clicking the node: the
        // detail panel opens with that Entity's content, and the search
        // resets to its empty state.
        Locator panel = page.locator("#entity-detail-panel");
        assertThat(panel).hasClass(Pattern.compile(OPEN_CLASS_PATTERN));
        assertThat(page.locator("#entity-detail-name")).containsText("Holmes");
        assertThat(input).hasValue("");
        assertThat(resultsList).isHidden();
    }

    @Test
    void aNonMatchingPrefixShowsNoResultsWithoutError() {
        loadDemoDatasetAndWaitReady();

        page.locator("#entity-search-input").fill("zzqqxx-nonexistent");

        assertThat(page.locator("#entity-search-results")).isHidden();
    }

    @Test
    void arrowKeysAndEnterAreKeyboardOperable() {
        loadDemoDatasetAndWaitReady();

        Locator input = page.locator("#entity-search-input");
        input.fill("holm");
        assertThat(page.locator("#entity-search-results"))
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(5000));

        input.press("ArrowDown");
        assertThat(page.locator(".entity-search-result.is-active")).hasCount(1);

        input.press("Enter");
        assertThat(page.locator("#entity-detail-panel")).hasClass(Pattern.compile(OPEN_CLASS_PATTERN));
    }

    @Test
    void theEntitySearchControlIsHiddenOnTheVectorSpaceTab() {
        loadDemoDatasetAndWaitReady();

        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();
        assertThat(page.locator(".replay-cta").last())
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        page.locator(".message.answer").last().locator(".compare-cta").click();
        assertThat(page.locator("#compare-panel"))
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15000));
        assertThat(page.locator("#entity-search")).isHidden();

        page.locator("#tab-vector-space").click();
        assertThat(page.locator("#vector-space-panel")).isVisible();
        assertThat(page.locator("#entity-search")).isHidden();

        page.locator("#tab-knowledge-graph").click();
        assertThat(page.locator("#entity-search")).isVisible();
    }

    @Test
    void entitySearchNeverOverlapsTheCollapsedSettingsButton() {
        // Story 10.1 regression, now guarding spec-11-7's replacement
        // control: both controls used to independently anchor
        // `position: absolute` to the same top-right corner with fixed pixel
        // offsets, and a toggle's real rendered height exceeded the
        // entity-search box's fixed `top`, so they visually overlapped.
        // The always-visible community/entity-type toggles are gone now
        // (collapsed behind the settings popover, spec-11-7), but the same
        // class of regression is still possible between the settings button
        // and entity-search — checked against real rendered geometry, not
        // CSS source values, since that's exactly what a source-level check
        // would have missed the first time. This only covers the small,
        // fixed-size *collapsed* button; see
        // entitySearchNeverOverlapsTheOpenSettingsPopover below for the
        // popover's own, much larger, rendered content.
        loadDemoDatasetAndWaitReady();

        assertControlsDoNotOverlap(page.locator("#canvas-settings-toggle"), page.locator("#entity-search"));
    }

    @Test
    void entitySearchNeverOverlapsTheCollapsedSettingsButtonWithTheDetailPanelOpen() {
        // Acceptance criterion: opening the entity detail panel shifts the
        // whole `.canvas-top-right-stack` leftward (to clear the panel) via
        // a dedicated override — both controls must still not overlap.
        loadDemoDatasetAndWaitReady();

        boolean fired = (boolean) page.evaluate(
                "identity => window.GraphCanvas.simulateTap(identity)", "sherlock holmes::person");
        org.assertj.core.api.Assertions.assertThat(fired)
                .withFailMessage("Node 'sherlock holmes::person' was not found on the rendered graph.")
                .isTrue();
        assertThat(page.locator("#entity-detail-panel")).hasClass(Pattern.compile(OPEN_CLASS_PATTERN));

        assertControlsDoNotOverlap(page.locator("#canvas-settings-toggle"), page.locator("#entity-search"));
    }

    @Test
    void entitySearchNeverOverlapsTheCollapsedSettingsButtonAtNarrowViewportWidth() {
        // Acceptance criterion: a narrow viewport tightens the stack's right
        // margin via a dedicated media-query override — both controls must
        // still not overlap.
        page.setViewportSize(600, 800);
        loadDemoDatasetAndWaitReady();

        assertControlsDoNotOverlap(page.locator("#canvas-settings-toggle"), page.locator("#entity-search"));
    }

    @Test
    void entitySearchNeverOverlapsTheOpenSettingsPopover() {
        // The collapsed-button check above can never catch an overlap
        // regression caused by the popover's real rendered content once it
        // is open — the popover only ever extends further down/right from
        // the button (`.canvas-settings-popover` is `position: absolute`),
        // so #entity-search must sit ahead of it in both DOM order and
        // markup order for this to hold.
        loadDemoDatasetAndWaitReady();

        page.locator("#canvas-settings-toggle").click();
        assertControlsDoNotOverlap(page.locator("#canvas-settings-popover"), page.locator("#entity-search"));
    }

    @Test
    void entitySearchNeverOverlapsTheOpenSettingsPopoverAtNarrowViewportWidth() {
        page.setViewportSize(600, 800);
        loadDemoDatasetAndWaitReady();

        page.locator("#canvas-settings-toggle").click();
        assertControlsDoNotOverlap(page.locator("#canvas-settings-popover"), page.locator("#entity-search"));
    }

    private void assertControlsDoNotOverlap(Locator first, Locator second) {
        assertThat(first).isVisible();
        assertThat(second).isVisible();

        BoundingBox firstBox = first.boundingBox();
        BoundingBox secondBox = second.boundingBox();
        org.assertj.core.api.Assertions.assertThat(firstBox).isNotNull();
        org.assertj.core.api.Assertions.assertThat(secondBox).isNotNull();

        boolean overlapsVertically = firstBox.y < secondBox.y + secondBox.height
                && secondBox.y < firstBox.y + firstBox.height;
        boolean overlapsHorizontally = firstBox.x < secondBox.x + secondBox.width
                && secondBox.x < firstBox.x + firstBox.width;

        org.assertj.core.api.Assertions.assertThat(overlapsVertically && overlapsHorizontally)
                .withFailMessage("%s and entity search %s bounding boxes overlap", firstBox, secondBox)
                .isFalse();
    }
}
