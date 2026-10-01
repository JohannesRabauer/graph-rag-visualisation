package dev.rabauer.graphrag.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Playwright UI tests for spec-10-4-load-a-new-corpus-after-one-is-active.md
 * (GitHub #23): once a Corpus is active, {@code #canvas-idle} used to be
 * hidden for good and the old {@code #workflow-restart-button} (buried in
 * {@code #workflow-recovery-actions}, FAILED-only, and a no-op) gave no real
 * way to load a second Corpus without a full page reload.
 *
 * <p>The restart control now lives beside {@code #corpus-chip} in the app
 * bar (shown/hidden as its pair) and is wired to a
 * {@code window.confirm(...)} gate, then a full {@code resetToIdleState()}
 * teardown that re-reveals {@code #canvas-idle} so the existing, unmodified
 * upload/demo-dataset handlers can run again.
 */
class LoadNewCorpusUiTest extends UiTestSupport {

    private static final String CONFIRM_MESSAGE =
            "Loading a new Corpus will discard the current one — continue?";

    /** A slow progress stream that never terminates on its own — mirrors
     * {@code ProgressStreamDisconnectedBannerUiTest}'s route-mocking pattern
     * — used to reliably catch the app in the BUILDING state. */
    private void mockSlowProgressStream() {
        page.route("**/api/corpora/*/progress", (Route route) -> route.fulfill(
                new Route.FulfillOptions()
                        .setStatus(200)
                        .setContentType("text/event-stream")
                        .setBody(
                                "event: heartbeat\n"
                                        + "data: {\"type\":\"heartbeat\",\"data\":{\"message\":\"Connection established.\"}}\n\n")));
    }

    private Locator restartButton() {
        return page.locator("#workflow-restart-button");
    }

    @Test
    void restartButtonIsReachableAndResetsDuringBuilding() {
        mockSlowProgressStream();

        page.navigate(baseUrl() + "/");
        page.locator("#demo-dataset-button").click();

        assertThat(page.locator("#corpus-chip"))
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        assertThat(restartButton())
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        assertThat(restartButton()).isEnabled();

        page.onceDialog(dialog -> {
            org.assertj.core.api.Assertions.assertThat(dialog.message()).isEqualTo(CONFIRM_MESSAGE);
            dialog.accept();
        });
        restartButton().click();

        assertThat(page.locator("#canvas-idle")).isVisible();
        assertThat(page.locator("#corpus-chip")).isHidden();
        assertThat(restartButton()).isHidden();
        assertThat(page.locator("#graph-canvas")).isHidden();
        assertThat(page.locator("#chat-panel")).isHidden();
        assertThat(page.locator("#corpus-file-input")).isEnabled();
        assertThat(page.locator("#demo-dataset-button")).isEnabled();
        assertThat(page.locator("#demo-offline-button")).isEnabled();
    }

    @Test
    void restartButtonIsReachableAndResetsDuringReady() {
        loadDemoDatasetAndWaitReady();

        assertThat(restartButton())
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        assertThat(restartButton()).isEnabled();

        page.onceDialog(dialog -> dialog.accept());
        restartButton().click();

        assertThat(page.locator("#canvas-idle")).isVisible();
        assertThat(page.locator("#corpus-chip")).isHidden();
        assertThat(restartButton()).isHidden();
    }

    @Test
    void cancellingTheConfirmationLeavesTheActiveCorpusUntouched() {
        loadDemoDatasetAndWaitReady();

        page.locator("#chat-input").fill("Tell me about Holmes.");
        page.locator("#chat-form .send-button").click();
        assertThat(page.locator(".message.question"))
                .containsText("Tell me about Holmes.", new LocatorAssertions.ContainsTextOptions().setTimeout(20000));
        assertThat(page.locator(".message.answer"))
                .hasCount(1, new LocatorAssertions.HasCountOptions().setTimeout(20000));

        page.onceDialog(com.microsoft.playwright.Dialog::dismiss);
        restartButton().click();

        // Nothing torn down: chip/canvas/chat all remain exactly as they were.
        assertThat(page.locator("#corpus-chip")).isVisible();
        assertThat(restartButton()).isVisible();
        assertThat(page.locator("#canvas-idle")).isHidden();
        assertThat(page.locator("#graph-canvas")).isVisible();
        assertThat(page.locator("#chat-panel")).isVisible();
        assertThat(page.locator(".message.question")).hasCount(1);
        assertThat(page.locator(".message.answer")).hasCount(1);
    }

    @Test
    void loadingASecondDemoDatasetAfterResetBuildsAndStreamsNormallyAgain() {
        loadDemoDatasetAndWaitReady();

        page.onceDialog(dialog -> dialog.accept());
        restartButton().click();
        assertThat(page.locator("#canvas-idle")).isVisible();

        // Second Corpus load through the re-shown #canvas-idle controls —
        // the existing, unmodified showCorpusChip flow just runs again.
        page.locator("#demo-dataset-button").click();

        assertThat(page.locator("#corpus-chip"))
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        assertThat(page.locator("#canvas-idle")).isHidden();
        assertThat(page.locator("#workflow-status-text"))
                .containsText("Ready", new LocatorAssertions.ContainsTextOptions().setTimeout(20000));
        assertThat(restartButton()).isVisible();

        page.locator("#chat-input").fill("Tell me about Holmes.");
        page.locator("#chat-form .send-button").click();
        assertThat(page.locator(".message.answer"))
                .hasCount(1, new LocatorAssertions.HasCountOptions().setTimeout(20000));
    }

    @Test
    void restartButtonIsReachableAndResetsDuringFailed() {
        page.route("**/api/corpora/*/progress", (Route route) -> route.fulfill(
                new Route.FulfillOptions()
                        .setStatus(200)
                        .setContentType("text/event-stream")
                        .setBody(
                                "event: error\n"
                                        + "data: {\"type\":\"error\",\"data\":{\"error\":\"Ingestion failed.\"}}\n\n")));

        page.navigate(baseUrl() + "/");
        page.locator("#demo-dataset-button").click();

        assertThat(page.locator("#workflow-status-text"))
                .containsText("failed", new LocatorAssertions.ContainsTextOptions().setTimeout(20000));
        assertThat(restartButton())
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        assertThat(restartButton()).isEnabled();

        page.onceDialog(dialog -> {
            org.assertj.core.api.Assertions.assertThat(dialog.message()).isEqualTo(CONFIRM_MESSAGE);
            dialog.accept();
        });
        restartButton().click();

        assertThat(page.locator("#canvas-idle")).isVisible();
        assertThat(page.locator("#corpus-chip")).isHidden();
        assertThat(restartButton()).isHidden();
        assertThat(page.locator("#corpus-file-input")).isEnabled();
        assertThat(page.locator("#demo-dataset-button")).isEnabled();
        assertThat(page.locator("#demo-offline-button")).isEnabled();
    }

    // Reproduces the switchCanvasTab ordering bug: once the Compare tab has ever
    // been opened this session, its own tab-switch cleanup would otherwise
    // re-show elements resetToIdleState just hid, because it un-hides
    // anything still carrying `dataset.hiddenByTabSwitch` from that earlier
    // switch. Setup modeled on VectorBaselineTriggerUiTest's own
    // LOCAL-answer-then-Compare flow.
    @Test
    void resettingAfterCompareWasOpenedLeavesEveryKnowledgeGraphControlHidden() {
        loadDemoDatasetAndWaitReady();

        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();
        Locator localAnswer = page.locator(".message.answer[data-mode='LOCAL']").last();
        assertThat(localAnswer.locator(".replay-cta"))
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));

        localAnswer.locator(".compare-cta").click();
        assertThat(page.locator("#compare-panel"))
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15000));
        assertThat(page.locator("#graph-canvas")).isHidden();

        page.onceDialog(dialog -> dialog.accept());
        restartButton().click();

        assertThat(page.locator("#canvas-idle")).isVisible();
        assertThat(page.locator("#graph-canvas")).isHidden();
        assertThat(page.locator("#entity-search")).isHidden();
        assertThat(page.locator("#graph-eyebrow")).isHidden();
        assertThat(page.locator("#community-toggle-wrap")).isHidden();
        assertThat(page.locator("#entity-type-toggle-wrap")).isHidden();
        assertThat(page.locator("#canvas-settings-toggle")).isHidden();
        assertThat(page.locator("#tab-compare")).isHidden();
        assertThat(page.locator("#compare-panel")).isHidden();
        assertThat(page.locator("#tab-vector-space")).hasCount(0);
    }
}
