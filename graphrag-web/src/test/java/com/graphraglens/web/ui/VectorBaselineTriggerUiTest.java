package com.graphraglens.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Playwright UI tests for Story 8-3: Compare CTA and Vector Space tab.
 *
 * <p>All tests use the offline deterministic stub (no OPENAI_API_KEY),
 * so vector-index chunks are built via the offline {@code LangChain4jEmbeddingPort}
 * during demo-dataset ingestion and are available immediately after "Ready".
 */
class VectorBaselineTriggerUiTest extends UiTestSupport {

    @Test
    void compareCTAAppearsOnLocalAnswerAndNotOnVectorAnswer() {
        loadDemoDatasetAndWaitReady();

        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();

        // Wait for the answer message with a replay CTA (means response arrived).
        Locator replayCta = page.locator(".replay-cta").last();
        assertThat(replayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));

        // The LOCAL answer must have a Compare CTA.
        Locator localAnswer = page.locator(".message.answer[data-mode='LOCAL']").last();
        assertThat(localAnswer.locator(".compare-cta")).isVisible();

        // Click Compare CTA and wait for the VECTOR answer to appear.
        localAnswer.locator(".compare-cta").click();
        Locator vectorAnswer = page.locator(".message.answer[data-mode='VECTOR']").last();
        assertThat(vectorAnswer).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15000));

        // VECTOR answer must NOT have its own Compare CTA.
        assertThat(vectorAnswer.locator(".compare-cta")).hasCount(0);
    }

    @Test
    void vectorSpaceTabIsRevealedAfterFirstComparisonAndTabSwitchingWorks() {
        loadDemoDatasetAndWaitReady();

        // Vector Space tab must be hidden before any comparison.
        assertThat(page.locator("#tab-vector-space")).isHidden();

        page.locator("#chat-input").fill("Who is Sherlock Holmes?");
        page.locator("#chat-form .send-button").click();

        Locator replayCta = page.locator(".replay-cta").last();
        assertThat(replayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));

        // Trigger comparison.
        page.locator(".message.answer").last().locator(".compare-cta").click();
        assertThat(page.locator(".message.answer[data-mode='VECTOR']").last())
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15000));

        // Vector Space tab is now visible and auto-switched to.
        assertThat(page.locator("#tab-vector-space")).isVisible();
        assertThat(page.locator("#tab-vector-space")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#tab-knowledge-graph")).hasAttribute("aria-selected", "false");

        // Vector space panel shows the answer text.
        assertThat(page.locator("#vector-space-panel")).isVisible();
        assertThat(page.locator("#vector-space-answer")).not().isEmpty();

        // Switch back to Knowledge Graph tab.
        page.locator("#tab-knowledge-graph").click();
        assertThat(page.locator("#tab-knowledge-graph")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#tab-vector-space")).hasAttribute("aria-selected", "false");
        assertThat(page.locator("#vector-space-panel")).isHidden();
    }

    @Test
    void canvasTabBarIsHiddenBeforeCorpusLoadAndVisibleAfter() {
        page.navigate(baseUrl() + "/");

        // Before corpus — tab bar is hidden.
        assertThat(page.locator("#canvas-tab-bar")).isHidden();

        // After demo dataset loads — tab bar appears.
        page.locator("#demo-dataset-button").click();
        assertThat(page.locator("#workflow-status-text"))
                .containsText("Ready", new LocatorAssertions.ContainsTextOptions().setTimeout(20000));
        assertThat(page.locator("#canvas-tab-bar")).isVisible();
    }
}
