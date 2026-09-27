package com.graphraglens.web.ui;

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
        assertThat(page.locator("#vector-space-panel"))
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15000));

        assertThat(page.locator("#entity-search")).isHidden();

        page.locator("#tab-knowledge-graph").click();
        assertThat(page.locator("#entity-search")).isVisible();
    }

    @Test
    void entitySearchNeverOverlapsTheCommunityToggle() {
        // Story 10.1 regression: both controls used to independently anchor
        // `position: absolute` to the same top-right corner with fixed pixel
        // offsets, and the community toggle's real rendered height (its
        // meta text wraps across several lines) exceeded the entity-search
        // box's fixed `top`, so they visually overlapped. Checked against
        // real rendered geometry, not CSS source values, since that's
        // exactly what a source-level check would have missed the first time.
        loadDemoDatasetAndWaitReady();

        assertControlsDoNotOverlap();
    }

    @Test
    void entitySearchNeverOverlapsTheCommunityToggleWithTheDetailPanelOpen() {
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

        assertControlsDoNotOverlap();
    }

    @Test
    void entitySearchNeverOverlapsTheCommunityToggleAtNarrowViewportWidth() {
        // Acceptance criterion: a narrow viewport tightens the stack's right
        // margin via a dedicated media-query override — both controls must
        // still not overlap.
        page.setViewportSize(600, 800);
        loadDemoDatasetAndWaitReady();

        assertControlsDoNotOverlap();
    }

    private void assertControlsDoNotOverlap() {
        Locator toggle = page.locator("#community-toggle-wrap");
        Locator search = page.locator("#entity-search");
        assertThat(toggle).isVisible();
        assertThat(search).isVisible();

        BoundingBox toggleBox = toggle.boundingBox();
        BoundingBox searchBox = search.boundingBox();
        org.assertj.core.api.Assertions.assertThat(toggleBox).isNotNull();
        org.assertj.core.api.Assertions.assertThat(searchBox).isNotNull();

        boolean overlapsVertically = toggleBox.y < searchBox.y + searchBox.height
                && searchBox.y < toggleBox.y + toggleBox.height;
        boolean overlapsHorizontally = toggleBox.x < searchBox.x + searchBox.width
                && searchBox.x < toggleBox.x + toggleBox.width;

        org.assertj.core.api.Assertions.assertThat(overlapsVertically && overlapsHorizontally)
                .withFailMessage("Community toggle %s and entity search %s bounding boxes overlap",
                        toggleBox, searchBox)
                .isFalse();
    }
}
