package dev.rabauer.graphrag.web.ui;

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
 * <p>The demo dataset's deterministic extraction always yields two
 * three-member Communities ("Irene Adler", "King", "Godfrey Norton" first,
 * then "Holmes", "Professor Moriarty", "Europe"), while "Sherlock Holmes",
 * with no Relationships, is below {@code MIN_COMMUNITY_SIZE} and gets no
 * hull at all. Toggle hulls off, ask a GLOBAL question (every Community
 * becomes a trace step, per {@code AnswerGlobalSearch}'s own class Javadoc),
 * and Replay until the Irene Adler Community's own step is current.
 */
class ReplayCommunityHullVisibilityUiTest extends UiTestSupport {

    private static final String IRENE_ADLER_IDENTITY = "irene adler::concept";

    @Test
    void communityStepHullIsVisibleDuringReplayEvenWhenToggledOff() {
        loadDemoDatasetAndWaitReady();

        // Turn the community-visualization toggle OFF before asking anything,
        // so every hull starts hidden. spec-11-7 (#36): the toggle no longer
        // renders permanently — open the settings popover first (its own
        // id/semantics are unchanged, only its container). Clicking the
        // wrapping <label> (rather than the checkbox directly) exercises the
        // same activation path a real click on the label's text would take.
        page.locator("#canvas-settings-toggle").click();
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

        // Step forward (there are exactly 2 Community steps) until the
        // caption names the "Irene Adler" community, the first step.
        boolean found = false;
        for (int i = 0; i < 5 && !found; i++) {
            if (caption.textContent().contains("Irene Adler")) {
                found = true;
                break;
            }
            if (stepForward.isDisabled()) {
                break;
            }
            stepForward.click();
        }
        assertThat(caption).containsText("Irene Adler");

        // Read this Community's id directly off the rendered graph
        // (Cytoscape's compound-node structure) and check its hull's actual
        // resolved opacity — no backend call involved.
        Number currentStepOpacity = (Number) page.evaluate(
                "identity => {"
                        + "  const communityId = window.GraphCanvas.communityIdForEntity(identity);"
                        + "  return communityId ? window.GraphCanvas.communityHullOpacity(communityId) : null;"
                        + "}",
                IRENE_ADLER_IDENTITY);

        org.assertj.core.api.Assertions.assertThat(currentStepOpacity).isNotNull();
        org.assertj.core.api.Assertions.assertThat(currentStepOpacity.doubleValue()).isGreaterThan(0.0);

        // "Sherlock Holmes" has no Community (below MIN_COMMUNITY_SIZE), so it
        // is a plain node without a hull parent.
        org.assertj.core.api.Assertions.assertThat(page.evaluate(
                "() => window.GraphCanvas.communityIdForEntity('sherlock holmes::person')")).isNull();

        // Sanity check the fix is actually scoped: a hull that is neither the
        // current nor the previous Replay step stays hidden — the toggle's
        // "with vs. without" comparison must still work for everything else
        // while Replay is open. "Professor Moriarty" sits in the second
        // Community, whose step comes after the current one (only the
        // current and the previous step are force-shown, by design, and the
        // first step has no previous one).
        Number untouchedHullOpacity = (Number) page.evaluate(
                "() => {"
                        + "  const communityId = window.GraphCanvas.communityIdForEntity('professor moriarty::concept');"
                        + "  return communityId ? window.GraphCanvas.communityHullOpacity(communityId) : null;"
                        + "}");

        org.assertj.core.api.Assertions.assertThat(untouchedHullOpacity).isNotNull();
        org.assertj.core.api.Assertions.assertThat(untouchedHullOpacity.doubleValue()).isEqualTo(0.0);
    }
}
