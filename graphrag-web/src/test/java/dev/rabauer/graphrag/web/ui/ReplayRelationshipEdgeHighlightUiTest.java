package dev.rabauer.graphrag.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Regression coverage for the reported "trace connections are not
 * highlighted" bug: Local Search never actually traversed a Relationship
 * (it independently keyword-scored every Relationship/Entity in the whole
 * corpus, and even when it happened to report a Relationship-shaped
 * answer, that step's identifier didn't correspond to any rendered edge —
 * see {@code AnswerLocalSearch} and {@code graph-canvas.js}'s
 * {@code highlightRetrievalStep}). This drives a real LOCAL query through
 * the actual UI and confirms the traversed edge is genuinely marked
 * {@code edge-traversed} on the canvas during Replay — not just that a
 * RELATIONSHIP step exists in the trace JSON.
 */
class ReplayRelationshipEdgeHighlightUiTest extends UiTestSupport {

    @Test
    void localSearchRelationshipStepHighlightsTheRealTraversedEdgeDuringReplay() {
        loadDemoDatasetAndWaitReady();

        // LOCAL is already the default mode; the demo corpus's deterministic
        // extraction gives "Irene Adler" a single Relationship (to "King")
        // that this question's keywords make the unambiguous one-hop match.
        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();

        Locator replayCta = page.locator(".replay-cta");
        assertThat(replayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        replayCta.click();

        Locator caption = page.locator("#replay-caption");
        Locator stepForward = page.locator("#replay-step-forward");

        // Step forward until the caption names the traversed relationship
        // (there are at most 3 steps: Entity, Relationship, Entity).
        boolean found = false;
        for (int i = 0; i < 3 && !found; i++) {
            if (caption.textContent().contains("traversed relationship")) {
                found = true;
                break;
            }
            if (stepForward.isDisabled()) {
                break;
            }
            stepForward.click();
        }
        assertThat(caption).containsText("traversed relationship");

        Boolean edgeIsTraversed = (Boolean) page.evaluate(
                "() => fetch('/api/traces/' + document.querySelector('.replay-cta').dataset.traceId)"
                        + "  .then(r => r.json())"
                        + "  .then(body => {"
                        + "    const step = (body.steps || []).find(s => s.kind === 'RELATIONSHIP');"
                        + "    return step ? window.GraphCanvas.elementHasClass(step.identifier, 'edge-traversed') : null;"
                        + "  })");

        org.assertj.core.api.Assertions.assertThat(edgeIsTraversed).isTrue();
    }
}
