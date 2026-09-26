package com.graphraglens.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
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
}
