package dev.rabauer.graphrag.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * The Retrieval Trace pane: every Replay opens a right-hand pane that names
 * the search mode, lists every step grouped by phase with the reason it was
 * taken, follows the current step, and jumps the Replay when a step is
 * clicked. The scrubber bar keeps a one-line caption, so a long step label
 * (a whole synthesized answer) is clipped there and shown in full in the pane.
 */
class TracePaneUiTest extends UiTestSupport {

    @Test
    void localReplayOpensThePaneWithPhasesReasonsAndClickableSteps() {
        loadDemoDatasetAndWaitReady();

        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();

        Locator replayCta = page.locator(".replay-cta").last();
        assertThat(replayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        replayCta.click();

        Locator pane = page.locator("#trace-pane");
        assertThat(pane).isVisible();
        // Docked beside the graph, like the help pane.
        org.assertj.core.api.Assertions.assertThat(pane.evaluate(
                "el => el.parentElement.classList.contains('canvas')")).isEqualTo(true);
        assertThat(page.locator("#trace-pane-mode")).hasText("Local Search");
        assertThat(page.locator("#trace-pane-intro")).containsText("Local Search starts from the entities");

        List<Map<String, Object>> traceSteps = fetchTraceSteps();
        org.assertj.core.api.Assertions.assertThat(traceSteps).isNotEmpty();

        Locator rows = page.locator("#trace-step-list .trace-step");
        assertThat(rows).hasCount(traceSteps.size());
        assertThat(page.locator("#trace-step-list .trace-phase-title").first()).hasText("Seed entities");
        assertThat(page.locator("#trace-step-list .trace-phase-why").first())
                .containsText("closest ones (up to 3) become starting points");

        // The current step is marked in the list and explained in the card.
        assertThat(rows.first()).hasClass(java.util.regex.Pattern.compile(".*is-current.*"));
        assertThat(page.locator("#trace-current-head")).containsText("Step 1 of " + traceSteps.size());
        assertThat(page.locator("#trace-current-label")).hasText((String) traceSteps.get(0).get("label"));
        assertThat(page.locator("#replay-hint")).containsText("Seed entity");
        assertThat(page.locator("#replay-phase")).containsText("Local 1/");

        if (traceSteps.size() > 1) {
            page.locator("#replay-step-forward").click();
            assertThat(rows.first()).not().hasClass(java.util.regex.Pattern.compile(".*is-current.*"));
            assertThat(rows.nth(1)).hasClass(java.util.regex.Pattern.compile(".*is-current.*"));
            assertThat(rows.first()).hasClass(java.util.regex.Pattern.compile(".*is-done.*"));

            // Clicking a step row jumps the Replay there.
            rows.last().click();
            assertThat(page.locator("#replay-step-counter"))
                    .hasText(pad(traceSteps.size()) + " / " + pad(traceSteps.size()));
            assertThat(rows.last()).hasClass(java.util.regex.Pattern.compile(".*is-current.*"));
            assertThat(page.locator("#trace-current-head"))
                    .containsText("Step " + traceSteps.size() + " of " + traceSteps.size());
        }

        // Hide and show again from the replay bar; closing Replay removes both.
        Locator toggle = page.locator("#replay-trace-toggle");
        assertThat(toggle).isVisible();
        assertThat(toggle).hasAttribute("aria-pressed", "true");
        page.locator("#trace-pane-close").click();
        assertThat(pane).isHidden();
        assertThat(toggle).hasAttribute("aria-pressed", "false");
        assertThat(toggle).hasText("Show trace pane");
        toggle.click();
        assertThat(pane).isVisible();
        assertThat(toggle).hasText("Hide trace pane");

        page.locator("#replay-close").click();
        assertThat(pane).isHidden();
        assertThat(toggle).isHidden();
    }

    @Test
    void aLongStepLabelIsClippedInTheBarAndShownInFullInThePane() {
        loadDemoDatasetAndWaitReady();

        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();

        Locator replayCta = page.locator(".replay-cta").last();
        assertThat(replayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));

        // Serve the real trace with one extra SYNTHESIS step whose label is a
        // whole, long answer — what the LLM-synthesized modes record.
        String longAnswer = "In this corpus graph, Irene Adler is the woman who outwitted Sherlock Holmes. "
                .repeat(8).trim();
        String traceJson = (String) page.evaluate(
                "answer => fetch('/api/traces/' + document.querySelector('.replay-cta').dataset.traceId)"
                        + "  .then(response => response.json())"
                        + "  .then(body => JSON.stringify({ traceId: body.traceId, steps: (body.steps || [])"
                        + "    .concat([{ kind: 'SYNTHESIS', identifier: '', label: answer }]) }))",
                longAnswer);
        page.route("**/api/traces/**", (Route route) -> route.fulfill(
                new Route.FulfillOptions().setStatus(200).setContentType("application/json").setBody(traceJson)));
        replayCta.click();

        Locator rows = page.locator("#trace-step-list .trace-step");
        assertThat(rows.last()).containsText("Answer");
        rows.last().click();

        Locator caption = page.locator("#replay-caption");
        assertThat(caption).containsText("synthesized answer");
        String captionText = caption.textContent();
        org.assertj.core.api.Assertions.assertThat(captionText).endsWith("…");
        org.assertj.core.api.Assertions.assertThat(captionText.length()).isLessThan(longAnswer.length());

        assertThat(page.locator("#trace-current-label")).hasText(longAnswer);
        // The whole label is rendered: the card scrolls, nothing is clipped away.
        org.assertj.core.api.Assertions.assertThat(page.locator("#trace-current-label").evaluate(
                "el => el.scrollHeight <= el.parentElement.scrollHeight")).isEqualTo(true);
        assertThat(page.locator("#replay-hint")).not().isEmpty();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchTraceSteps() {
        return (List<Map<String, Object>>) page.evaluate(
                "() => fetch('/api/traces/' + document.querySelector('.replay-cta').dataset.traceId)"
                        + "  .then(response => response.json())"
                        + "  .then(body => body.steps || [])");
    }

    private static String pad(int value) {
        String text = String.valueOf(value);
        return text.length() < 2 ? "0" + text : text;
    }
}
