package com.graphraglens.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Route;
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

    @Test
    void openingReplayWhileVectorSpaceTabIsActiveSwitchesBackToKnowledgeGraphTab() {
        loadDemoDatasetAndWaitReady();

        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();

        // Scoped to the LOCAL answer specifically — a bare ".replay-cta"
        // locator would re-resolve to the VECTOR answer's own Replay CTA
        // once Compare adds it below (Story 8.5's own trace also renders
        // one), since Playwright locators re-query at click time.
        Locator localAnswer = page.locator(".message.answer[data-mode='LOCAL']").last();
        Locator replayCta = localAnswer.locator(".replay-cta");
        assertThat(replayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));

        // Trigger a comparison — auto-switches to Vector Space on first reveal.
        localAnswer.locator(".compare-cta").click();
        assertThat(page.locator(".message.answer[data-mode='VECTOR']").last())
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15000));
        assertThat(page.locator("#vector-space-panel")).isVisible();
        assertThat(page.locator("#replay-scrubber")).isHidden();

        // Opening Replay on the original LOCAL answer must bring the
        // Knowledge Graph tab back into view — the two tabs stay mutually
        // exclusive regardless of which surface triggers the transition.
        replayCta.click();
        assertThat(page.locator("#replay-scrubber")).isVisible();
        assertThat(page.locator("#vector-space-panel")).isHidden();
        assertThat(page.locator("#tab-knowledge-graph")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#tab-vector-space")).hasAttribute("aria-selected", "false");
    }

    @Test
    void repeatedComparisonsDoNotForceTheUserBackToVectorSpaceTabOnceRevealed() {
        loadDemoDatasetAndWaitReady();

        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();
        assertThat(page.locator(".replay-cta").last())
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));

        // First comparison reveals and auto-switches to Vector Space.
        page.locator(".message.answer").last().locator(".compare-cta").click();
        assertThat(page.locator(".message.answer[data-mode='VECTOR']").last())
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15000));
        assertThat(page.locator("#tab-vector-space")).hasAttribute("aria-selected", "true");

        // User manually returns to Knowledge Graph.
        page.locator("#tab-knowledge-graph").click();
        assertThat(page.locator("#tab-knowledge-graph")).hasAttribute("aria-selected", "true");

        // Ask another question and compare again — must NOT yank the user
        // back to Vector Space now that the tab has already been revealed.
        page.locator("#chat-input").fill("Who is Sherlock Holmes?");
        page.locator("#chat-form .send-button").click();
        assertThat(page.locator(".replay-cta").last())
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        page.locator(".message.answer").last().locator(".compare-cta").click();
        assertThat(page.locator(".message.answer[data-mode='VECTOR']"))
                .hasCount(2, new LocatorAssertions.HasCountOptions().setTimeout(15000));

        assertThat(page.locator("#tab-knowledge-graph")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#tab-vector-space")).hasAttribute("aria-selected", "false");
    }

    @Test
    void aFailedComparisonShowsTheErrorBannerAndReEnablesTheCompareCTA() {
        loadDemoDatasetAndWaitReady();

        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();
        assertThat(page.locator(".replay-cta").last())
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));

        page.route("**/api/corpora/*/query", (Route route) -> route.fulfill(
                new Route.FulfillOptions().setStatus(500).setContentType("application/json")
                        .setBody("{\"error\":\"boom\"}")));

        Locator compareCta = page.locator(".message.answer").last().locator(".compare-cta");
        compareCta.click();

        assertThat(page.locator("#error-banner")).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10000));
        assertThat(compareCta).isEnabled();
        assertThat(compareCta).hasText("\u21BB Compare with Vector Search");
        assertThat(page.locator(".message.answer[data-mode='VECTOR']")).hasCount(0);
    }

    @Test
    void vectorSpaceTabIsKeyboardReachableFromTheKnowledgeGraphTab() {
        loadDemoDatasetAndWaitReady();

        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();
        assertThat(page.locator(".replay-cta").last())
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        page.locator(".message.answer").last().locator(".compare-cta").click();
        assertThat(page.locator(".message.answer[data-mode='VECTOR']").last())
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15000));

        // Move focus back to Knowledge Graph, then reach Vector Space purely
        // via the keyboard (ARIA tabs pattern: ArrowRight moves focus and
        // activates the next tab).
        page.locator("#tab-knowledge-graph").click();
        page.locator("#tab-knowledge-graph").focus();
        page.keyboard().press("ArrowRight");

        assertThat(page.locator("#tab-vector-space")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#tab-vector-space")).isFocused();
        assertThat(page.locator("#vector-space-panel")).isVisible();

        page.keyboard().press("ArrowLeft");
        assertThat(page.locator("#tab-knowledge-graph")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#tab-knowledge-graph")).isFocused();
    }

    @Test
    void replayingAVectorAnswerLabelsStepsAsEmbeddedQueryAndRetrievedChunkNotMatchedEntity() {
        loadDemoDatasetAndWaitReady();

        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();
        assertThat(page.locator(".replay-cta").last())
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));

        page.locator(".message.answer").last().locator(".compare-cta").click();
        Locator vectorAnswer = page.locator(".message.answer[data-mode='VECTOR']").last();
        assertThat(vectorAnswer).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15000));

        // The VECTOR answer itself has no Compare CTA, but the pre-existing
        // Replay CTA logic renders unconditionally whenever a traceId is
        // present (Story 3.3), so it appears here too — replaying it must
        // label its VECTOR_QUERY_EMBEDDED/VECTOR_CHUNK steps meaningfully.
        Locator vectorReplayCta = vectorAnswer.locator(".replay-cta");
        assertThat(vectorReplayCta).isVisible();
        vectorReplayCta.click();

        Locator caption = page.locator("#replay-caption");
        assertThat(caption).isVisible();
        assertThat(caption).not().containsText("matched entity");
        assertThat(caption).containsText("embedded query");
    }

    @Test
    void vectorSpaceScatterRendersCorpusChunksAndTheQueryDotDuringReplay() {
        loadDemoDatasetAndWaitReady();

        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();
        assertThat(page.locator(".replay-cta").last())
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));

        page.locator(".message.answer").last().locator(".compare-cta").click();
        Locator vectorAnswer = page.locator(".message.answer[data-mode='VECTOR']").last();
        assertThat(vectorAnswer).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15000));

        // Story 8.5: the corpus's chunk scatter renders as soon as the tab is
        // revealed — no Replay needed to see the settled layout.
        assertThat(page.locator(".vector-space-chunk-dot").first())
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10000));

        // Replaying the VECTOR answer plots the query dot and highlights its
        // top-k retrieved chunks with connecting hit lines.
        vectorAnswer.locator(".replay-cta").click();
        page.locator("#replay-step-forward").click();

        assertThat(page.locator(".vector-space-query-dot"))
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10000));
        assertThat(page.locator(".vector-space-hit-line").first()).isVisible();
        assertThat(page.locator(".vector-space-chunk-dot.is-hit").first()).isVisible();
    }
}
