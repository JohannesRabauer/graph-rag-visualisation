package com.graphraglens.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Regression coverage for the Replay/community-hull visibility bug fixed in
 * {@code graph-canvas.js} (see EXPERIENCE.md's Component Patterns and
 * deferred-work.md's Story 5.2 entry): a Retrieval Trace step that touches a
 * Community must be visible during Replay even if the community-
 * visualization toggle is currently OFF — otherwise the step advances with
 * nothing on screen to show for it.
 *
 * <p>The demo dataset's deterministic extraction always yields three
 * Communities, one of which ("Sherlock Holmes") is a singleton with no
 * Relationships — a clean, unambiguous target: toggle hulls off, ask a
 * GLOBAL question (every Community becomes a trace step, per
 * {@code AnswerGlobalSearch}'s own class Javadoc), and Replay through until
 * this Community's own step is current.
 */
class ReplayCommunityHullVisibilityUiTest extends UiTestSupport {

    private static final String SHERLOCK_HOLMES_IDENTITY = "sherlock holmes::person";

    @Test
    void communityStepHullIsVisibleDuringReplayEvenWhenToggledOff() {
        loadDemoDatasetAndWaitReady();

        // Turn the community-visualization toggle OFF before asking anything,
        // so every hull starts hidden. Clicking the wrapping <label> (rather
        // than the checkbox directly) exercises the same activation path a
        // real click on the label's text would take.
        page.locator("label.community-toggle").click();

        // GLOBAL Search puts every Community on the trace (AnswerGlobalSearch
        // always adds one COMMUNITY step per Community, matched or not). The
        // radio input itself is visually hidden in favor of its custom dot
        // (instrument.css) — click the label, same as a real click would land.
        page.locator("label.mode-choice-option:has(input[value='GLOBAL'])").click();
        page.locator("#chat-input").fill("Tell me about the corpus.");
        page.locator("#chat-form .send-button").click();

        Locator replayCta = page.locator(".replay-cta");
        assertThat(replayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        replayCta.click();

        Locator scrubber = page.locator("#replay-scrubber");
        assertThat(scrubber).not().isHidden();

        Locator caption = page.locator("#replay-caption");
        Locator stepForward = page.locator("#replay-step-forward");

        // Step forward (there are exactly 3 Community steps) until the
        // caption names the singleton "Sherlock Holmes" community.
        boolean found = false;
        for (int i = 0; i < 5 && !found; i++) {
            if (caption.textContent().contains("Sherlock Holmes")) {
                found = true;
                break;
            }
            if (stepForward.isDisabled()) {
                break;
            }
            stepForward.click();
        }
        assertThat(caption).containsText("Sherlock Holmes");

        // Ask the page itself which Community is the singleton (rather than
        // hard-coding a community id) and read its hull's actual resolved
        // opacity through the GraphCanvas test hook, which talks to the
        // real Cytoscape instance the page is running.
        Number singletonOpacity = (Number) page.evaluate(
                "() => fetch('/api/graph').then(r => r.json()).then(body => {"
                        + "  const singleton = (body.communities || []).find(c => "
                        + "    (c.memberEntityIdentities || []).length === 1 && "
                        + "    (c.memberEntityIdentities || [])[0] === '" + SHERLOCK_HOLMES_IDENTITY + "');"
                        + "  return singleton ? window.GraphCanvas.communityHullOpacity(singleton.communityId) : null;"
                        + "})");

        org.assertj.core.api.Assertions.assertThat(singletonOpacity).isNotNull();
        org.assertj.core.api.Assertions.assertThat(singletonOpacity.doubleValue()).isGreaterThan(0.0);

        // Sanity check the fix is actually scoped: a hull that is neither the
        // current nor the previous Replay step stays hidden — the toggle's
        // "with vs. without" comparison must still work for everything else
        // while Replay is open. ("previous" is also force-shown by design —
        // that community contains "King", the current step's neighbor in the
        // demo corpus's deterministic extraction — so this excludes both.)
        Number untouchedHullOpacity = (Number) page.evaluate(
                "() => fetch('/api/graph').then(r => r.json()).then(body => {"
                        + "  const untouched = (body.communities || []).find(c => "
                        + "    !(c.memberEntityIdentities || []).includes('" + SHERLOCK_HOLMES_IDENTITY + "') && "
                        + "    !(c.memberEntityIdentities || []).includes('king::concept'));"
                        + "  return untouched ? window.GraphCanvas.communityHullOpacity(untouched.communityId) : null;"
                        + "})");

        org.assertj.core.api.Assertions.assertThat(untouchedHullOpacity).isNotNull();
        org.assertj.core.api.Assertions.assertThat(untouchedHullOpacity.doubleValue()).isEqualTo(0.0);
    }
}
