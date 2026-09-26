package com.graphraglens.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Story 9.7: captures each named moment from the live demo script's own
 * shot list (SCR-1 through SCR-12) as a PNG under
 * {@code graphrag-web/target/demo-screenshots/}, so the demo script's
 * screenshots stay current for free as the UI evolves instead of a manual
 * re-capture pass every time. The CI workflow uploads that directory as a
 * build artifact (see {@code .github/workflows/ci.yml}).
 *
 * <p>SCR-3 and SCR-4 (mid-ingestion / communities forming) are captured as
 * soon as possible after triggering ingestion, without waiting — the
 * deterministic offline stub can complete in well under a second, so these
 * two are a best-effort snapshot of "as early as this suite could look",
 * not a guaranteed mid-progress frame. Every other shot asserts the exact
 * state the demo script itself calls for before capturing.
 */
class DemoScriptScreenshotsUiTest extends UiTestSupport {

    private static final Path SCREENSHOT_DIR = Paths.get("target", "demo-screenshots");

    private void shoot(String id) {
        page.screenshot(new Page.ScreenshotOptions().setPath(SCREENSHOT_DIR.resolve(id + ".png")));
    }

    private void stepForwardIfPossible() {
        Locator stepForward = page.locator("#replay-step-forward");
        if (!Boolean.TRUE.equals(stepForward.isDisabled())) {
            stepForward.click();
        }
    }

    @Test
    void capturesEveryNamedDemoScriptMoment() {
        // SCR-1: empty state, nothing ingested.
        page.navigate(baseUrl() + "/");
        assertThat(page.locator("#canvas-idle")).isVisible();
        shoot("SCR-1");

        // SCR-2: just after choosing the Demo Dataset — corpus chip named,
        // chat panel slid in, composer not yet submittable.
        page.locator("#demo-dataset-button").click();
        assertThat(page.locator("#corpus-chip")).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10000));
        assertThat(page.locator("#chat-panel")).isVisible();
        shoot("SCR-2");

        // SCR-3/4: as-early-as-possible snapshots of ingestion/community
        // formation in progress (see class javadoc on why these two are
        // best-effort rather than a guaranteed mid-progress frame).
        shoot("SCR-3");
        shoot("SCR-4");

        // SCR-5: ingestion complete, ready state, full graph settled.
        assertThat(page.locator("#workflow-status-text"))
                .containsText("Ready", new LocatorAssertions.ContainsTextOptions().setTimeout(20000));
        shoot("SCR-5");

        // SCR-6: entity detail panel open.
        boolean tapped = (boolean) page.evaluate(
                "identity => window.GraphCanvas.simulateTap(identity)", "sherlock holmes::person");
        org.assertj.core.api.Assertions.assertThat(tapped).isTrue();
        assertThat(page.locator("#entity-detail-panel"))
                .hasClass(java.util.regex.Pattern.compile(".*\\bis-open\\b.*"));
        shoot("SCR-6");
        // Close it again so it doesn't cover the graph in later shots.
        page.evaluate("identity => window.GraphCanvas.simulateTap(identity)", "sherlock holmes::person");

        // SCR-7: Local Search answered.
        page.locator("#chat-input").fill("Who is Sherlock Holmes?");
        page.locator("#chat-form .send-button").click();
        Locator localReplayCta = page.locator(".message.answer[data-mode='LOCAL']").last().locator(".replay-cta");
        assertThat(localReplayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        shoot("SCR-7");

        // SCR-8: Local trace mid-replay. The deterministic offline stub's
        // trace for a given question may be as short as a single step — if
        // there's nowhere further to step, the capture still shows the
        // scrubber itself, just without an actual "step forward".
        localReplayCta.click();
        assertThat(page.locator("#replay-scrubber")).isVisible();
        stepForwardIfPossible();
        shoot("SCR-8");
        page.locator("#replay-close").click();

        // SCR-9: Global Search answered.
        page.locator("label.mode-choice-option:has(input[value='GLOBAL'])").click();
        page.locator("#chat-input").fill("What are the major themes across these stories?");
        page.locator("#chat-form .send-button").click();
        assertThat(page.locator(".message.answer[data-mode='GLOBAL']").last())
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        shoot("SCR-9");

        // SCR-10: DRIFT Search answered.
        page.locator("label.mode-choice-option:has(input[value='DRIFT'])").click();
        page.locator("#chat-input").fill("How do Holmes's relationships shape his investigations?");
        page.locator("#chat-form .send-button").click();
        Locator driftReplayCta = page.locator(".message.answer[data-mode='DRIFT']").last().locator(".replay-cta");
        assertThat(driftReplayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        shoot("SCR-10");

        // SCR-11: DRIFT tree mid-replay.
        driftReplayCta.click();
        assertThat(page.locator("#drift-tree")).isVisible();
        stepForwardIfPossible();
        shoot("SCR-11");
        page.locator("#replay-close").click();

        // SCR-12: Vector Space tab open with the vector-only answer.
        page.locator(".message.answer").last().locator(".compare-cta").click();
        assertThat(page.locator("#vector-space-panel"))
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(15000));
        shoot("SCR-12");
    }
}
