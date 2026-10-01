package dev.rabauer.graphrag.web.ui;

import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Playwright UI tests for Story 9.1: the demo-safe offline mode. Distinct
 * from {@link UiTestSupport#loadDemoDatasetAndWaitReady()} (the live demo
 * dataset), these drive the "Use the Offline Demo — no API calls" button.
 */
class OfflineDemoUiTest extends UiTestSupport {

    @Test
    void offlineDemoBuildsToReadyAndDisablesTheComposer() {
        page.navigate(baseUrl() + "/");

        page.locator("#demo-offline-button").click();
        assertThat(page.locator("#workflow-status-text"))
                .containsText("Ready", new LocatorAssertions.ContainsTextOptions().setTimeout(20000));

        // Corpus chip must never read like the live demo — distinct name.
        assertThat(page.locator("#corpus-chip"))
                .containsText("Sherlock Holmes — Offline Demo");

        // The composer is structurally disabled, not just warned about —
        // no way to even attempt typing a question.
        assertThat(page.locator("#chat-input")).isDisabled();
        assertThat(page.locator("#chat-form .send-button")).isDisabled();
        assertThat(page.locator("#composer-offline-note")).isVisible();
        assertThat(page.locator("#composer-offline-note")).containsText("pre-recorded");
    }

    @Test
    void theLiveDemoDatasetOnAFreshPageLoadLeavesTheComposerFullyEnabled() {
        // A separate, fresh page load — not a mid-session corpus swap (the
        // idle/upload panel collapses once any corpus loads, same as the
        // live demo dataset's own behavior; there's no in-session "switch
        // corpus" affordance today) — confirms Story 9.1's offline-only
        // disabling never leaks onto the live path.
        loadDemoDatasetAndWaitReady();

        assertThat(page.locator("#chat-input")).isEnabled();
        assertThat(page.locator("#chat-form .send-button")).isEnabled();
        assertThat(page.locator("#composer-offline-note")).isHidden();
        assertThat(page.locator("#corpus-chip")).not().containsText("Offline");
    }
}
