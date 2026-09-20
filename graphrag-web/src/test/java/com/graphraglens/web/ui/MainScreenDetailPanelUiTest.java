package com.graphraglens.web.ui;

import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Real-browser coverage for the Entity detail panel now that it lives on
 * the main screen's own canvas (merged from the former separate Explore
 * page, 2026-09-20 UX pass — see deferred-work.md's Story 6.2 entry for
 * the original gap this replaces). Node interactions go through the
 * {@code GraphCanvas.simulateTap} test hook, matching the pattern already
 * established for the Explore page's own equivalent coverage.
 */
class MainScreenDetailPanelUiTest extends UiTestSupport {

    private static final String OPEN_CLASS_PATTERN = ".*\\bis-open\\b.*";

    private void tap(String identity) {
        boolean fired = (boolean) page.evaluate(
                "identity => window.GraphCanvas.simulateTap(identity)", identity);
        org.assertj.core.api.Assertions.assertThat(fired)
                .withFailMessage("Node '%s' was not found on the rendered graph.", identity)
                .isTrue();
    }

    @Test
    void clickingEntitiesOnTheMainScreenOpensClosesAndSwapsTheDetailPanelAndHullTapsAreIgnored() {
        loadDemoDatasetAndWaitReady();

        Locator panel = page.locator("#entity-detail-panel");
        Locator name = page.locator("#entity-detail-name");
        Locator type = page.locator("#entity-detail-type");

        // Tap an Entity — the panel opens with that Entity's name/type. This
        // Entity has no Relationships in the demo corpus's deterministic
        // extraction, exercising the "No relationships" fallback line too.
        tap("sherlock holmes::person");
        assertThat(panel).hasClass(java.util.regex.Pattern.compile(OPEN_CLASS_PATTERN));
        assertThat(name).containsText("Sherlock Holmes");
        assertThat(type).containsText("Person");
        assertThat(page.locator("#entity-detail-relationships")).containsText("No relationships");

        // Tap the SAME Entity again — the panel closes.
        tap("sherlock holmes::person");
        assertThat(panel).not().hasClass(java.util.regex.Pattern.compile(OPEN_CLASS_PATTERN));

        // Tap a DIFFERENT Entity — the panel opens directly with the new
        // content (no separate close step needed).
        tap("sherlock holmes::person");
        assertThat(name).containsText("Sherlock Holmes");
        tap("holmes::person");
        assertThat(panel).hasClass(java.util.regex.Pattern.compile(OPEN_CLASS_PATTERN));
        assertThat(name).containsText("Holmes");

        // Close it, then tap a Community hull — hull taps are routed to
        // focusCommunity, never to the Entity detail callback, so the panel
        // must stay closed.
        tap("holmes::person");
        assertThat(panel).not().hasClass(java.util.regex.Pattern.compile(OPEN_CLASS_PATTERN));

        // "King" is in a different Community from Sherlock Holmes (demo
        // corpus's deterministic extraction) — read the community id
        // directly off the rendered graph rather than any backend call.
        String communityId = (String) page.evaluate(
                "() => window.GraphCanvas.communityIdForEntity('king::concept')");
        org.assertj.core.api.Assertions.assertThat(communityId).isNotNull();
        tap("community::" + communityId);
        assertThat(panel).not().hasClass(java.util.regex.Pattern.compile(OPEN_CLASS_PATTERN));
    }

    @Test
    void detailPanelAndRetrievalTraceReplayCoexistOnTheSameCanvas() {
        // Explicit design decision: unlike a design where opening one closes
        // the other, both stay visible together on the merged canvas.
        loadDemoDatasetAndWaitReady();

        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();
        Locator replayCta = page.locator(".replay-cta");
        assertThat(replayCta).isVisible();
        replayCta.click();

        Locator scrubber = page.locator("#replay-scrubber");
        assertThat(scrubber).not().isHidden();

        Locator panel = page.locator("#entity-detail-panel");
        tap("holmes::person");

        assertThat(panel).hasClass(java.util.regex.Pattern.compile(OPEN_CLASS_PATTERN));
        assertThat(scrubber).not().isHidden();
    }
}
