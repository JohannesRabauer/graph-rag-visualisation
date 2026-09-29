package com.graphraglens.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Regression coverage for spec-11-3-fix-retrieval-trace-replay-not-rendering.md
 * (GitHub #32): the investigation found no reproducible regression in the
 * Replay rendering path itself, but flagged the intersection between Replay
 * and Story 10.4's "Start over with a new corpus" reset flow
 * ({@code resetToIdleState()}, see {@code LoadNewCorpusUiTest}) as untested.
 * This drives that exact sequence — open Replay for a first Corpus's answer,
 * reset, load a second Corpus, ask another question — and confirms the
 * Replay CTA still appears and opens correctly with working step
 * highlighting for the second Corpus's answer.
 */
class ReplayAfterCorpusResetUiTest extends UiTestSupport {

    @Test
    void replayStillWorksForASecondCorpusLoadedAfterAResetDiscardsTheFirst() {
        loadDemoDatasetAndWaitReady();

        // First Corpus: ask a question and confirm Replay opens and works,
        // exactly like the existing Replay tests already do.
        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();

        Locator replayCta = page.locator(".replay-cta");
        assertThat(replayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(40000));
        replayCta.click();

        Locator scrubber = page.locator("#replay-scrubber");
        assertThat(scrubber).not().isHidden();
        assertReplayHighlightsAStepOnTheCanvas();

        page.locator("#replay-close").click();
        assertThat(scrubber).isHidden();

        // Story 10.4's reset flow: confirm the "Start over with a new
        // corpus" dialog and land back on the idle canvas.
        page.onceDialog(dialog -> dialog.accept());
        page.locator("#workflow-restart-button").click();
        assertThat(page.locator("#canvas-idle")).isVisible();

        // Second Corpus, loaded through the re-shown idle controls.
        // Wait for #workflow-status to become visible again before checking
        // its text: resetToIdleState() hides it but leaves the first
        // Corpus's stale "Ready" textContent in place, so checking text
        // alone (ignoring the hidden attribute) can match that leftover
        // state before the second Corpus's own render ever runs.
        page.locator("#demo-dataset-button").click();
        assertThat(page.locator("#workflow-status"))
                .not().isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(40000));
        assertThat(page.locator("#workflow-status-text"))
                .containsText("Ready", new LocatorAssertions.ContainsTextOptions().setTimeout(90000));

        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();

        Locator secondReplayCta = page.locator(".replay-cta").last();
        assertThat(secondReplayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(40000));
        secondReplayCta.click();

        assertThat(scrubber).not().isHidden();
        assertReplayHighlightsAStepOnTheCanvas();
    }
}
