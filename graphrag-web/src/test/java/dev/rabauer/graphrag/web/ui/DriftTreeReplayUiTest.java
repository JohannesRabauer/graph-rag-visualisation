package dev.rabauer.graphrag.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

class DriftTreeReplayUiTest extends UiTestSupport {

    @Test
    void driftReplayRendersTheBranchingTreeAndTracksReplayPhases() {
        loadDemoDatasetAndWaitReady();

        page.locator("label.mode-choice-option:has(input[value='DRIFT'])").click();
        // Names one member of each demo Community, so both tie for the best
        // keyword score and DRIFT spawns (at least) two branches.
        page.locator("#chat-input").fill("Tell me about Holmes and the King.");
        page.locator("#chat-form .send-button").click();

        Locator replayCta = page.locator(".replay-cta");
        assertThat(replayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        replayCta.click();

        Locator tree = page.locator("#drift-tree");
        assertThat(tree).isVisible();
        assertThat(page.locator(".drift-tree-root")).hasCount(1);
        assertThat(page.locator(".drift-tree-final")).hasCount(1);
        Map<String, Object> rootState = readDriftTreeState();
        int branchCount = ((Number) rootState.get("branchCount")).intValue();
        org.assertj.core.api.Assertions.assertThat(branchCount).isGreaterThanOrEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(rootState.get("rootCurrent")).isEqualTo(true);
        org.assertj.core.api.Assertions.assertThat(rootState.get("currentBranch")).isEqualTo(-1);
        org.assertj.core.api.Assertions.assertThat(rootState.get("resolvedBranches")).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(rootState.get("upcomingBranches")).isEqualTo(branchCount);
        org.assertj.core.api.Assertions.assertThat(rootState.get("finalCurrent")).isEqualTo(false);

        List<Map<String, Object>> traceSteps = fetchTraceSteps();
        List<String> spawnedLabels = traceSteps.stream()
                .filter(step -> "SUB_QUESTION_SPAWNED".equals(step.get("kind")))
                .map(step -> (String) step.get("label"))
                .toList();
        org.assertj.core.api.Assertions.assertThat(spawnedLabels).hasSize(branchCount);
        List<String> branchNodeLabels = page.locator(".drift-tree-branch-node").allTextContents();
        org.assertj.core.api.Assertions.assertThat(branchNodeLabels).containsExactlyElementsOf(spawnedLabels);

        Locator caption = page.locator("#replay-caption");
        Locator stepForward = page.locator("#replay-step-forward");
        Locator stepBack = page.locator("#replay-step-back");

        advanceUntilCaptionContains(caption, stepForward, "spawned sub-question", traceSteps.size());

        Map<String, Object> spawnedState = readDriftTreeState();
        org.assertj.core.api.Assertions.assertThat(spawnedState.get("rootCurrent")).isEqualTo(false);
        org.assertj.core.api.Assertions.assertThat(spawnedState.get("currentBranch")).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(spawnedState.get("resolvedBranches")).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(spawnedState.get("upcomingBranches")).isEqualTo(branchCount - 1);
        org.assertj.core.api.Assertions.assertThat(spawnedState.get("finalCurrent")).isEqualTo(false);
        assertThat(page.locator(".drift-tree-branch").nth(1).locator(".drift-tree-line").first())
                .hasClass(java.util.regex.Pattern.compile(".*is-upcoming.*"));
        assertSpawnOrSynthesisDoesNotHighlightCommunityNode("SUB_QUESTION_SPAWNED");

        stepForward.click();
        assertBranchZeroFirstEntityStillHighlightsOnCanvas(traceSteps);

        advanceUntilCaptionContains(caption, stepForward, "synthesized answer", traceSteps.size());

        Map<String, Object> synthesisState = readDriftTreeState();
        org.assertj.core.api.Assertions.assertThat(synthesisState.get("rootCurrent")).isEqualTo(false);
        org.assertj.core.api.Assertions.assertThat(synthesisState.get("currentBranch")).isEqualTo(-1);
        org.assertj.core.api.Assertions.assertThat(synthesisState.get("resolvedBranches")).isEqualTo(branchCount);
        org.assertj.core.api.Assertions.assertThat(synthesisState.get("upcomingBranches")).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(synthesisState.get("finalCurrent")).isEqualTo(true);
        assertThat(page.locator(".drift-tree-branch").first().locator(".drift-tree-resolved")).isVisible();
        org.assertj.core.api.Assertions.assertThat(
                        page.locator(".drift-tree-branch").first().locator(".drift-tree-resolved").textContent())
                .contains("resolved");
        assertSpawnOrSynthesisDoesNotHighlightCommunityNode("SYNTHESIS");

        stepBack.click();

        Map<String, Object> steppedBackState = readDriftTreeState();
        org.assertj.core.api.Assertions.assertThat(steppedBackState.get("currentBranch")).isEqualTo(branchCount - 1);
        org.assertj.core.api.Assertions.assertThat(steppedBackState.get("resolvedBranches")).isEqualTo(branchCount - 1);
        org.assertj.core.api.Assertions.assertThat(steppedBackState.get("finalCurrent")).isEqualTo(false);

        page.locator("#replay-close").click();
        assertThat(tree).isHidden();
        assertThat(page.locator(".drift-tree-branch")).hasCount(0);
    }

    /**
     * Story 15.3: a synthesized DRIFT trace reads TEXT_UNIT passages inside its
     * branches. The demo runs offline, so the trace is rewritten in the page
     * into that shape: one TEXT_UNIT at the end of every branch.
     */
    @Test
    void aSynthesizedDriftTraceWithTextUnitStepsReplaysWithoutErrorsAndReachesSynthesisLast() {
        List<String> pageErrors = new java.util.ArrayList<>();
        page.onPageError(pageErrors::add);
        loadDemoDatasetAndWaitReady();
        page.evaluate("() => {"
                + "  const originalFetch = window.fetch;"
                + "  window.fetch = (input, init) => originalFetch(input, init).then(response => {"
                + "    const url = typeof input === 'string' ? input : input.url;"
                + "    if (!url.includes('/api/traces/') || !response.ok) { return response; }"
                + "    return response.json().then(body => {"
                + "      const steps = [];"
                + "      let inBranch = false;"
                + "      (body.steps || []).forEach(step => {"
                + "        if (inBranch && (step.kind === 'SUB_QUESTION_SPAWNED' || step.kind === 'SYNTHESIS')) {"
                + "          steps.push({ kind: 'TEXT_UNIT', identifier: 'tu-' + steps.length, label: 'A passage.' });"
                + "        }"
                + "        if (step.kind === 'SUB_QUESTION_SPAWNED') { inBranch = true; }"
                + "        if (step.kind === 'SYNTHESIS') { inBranch = false; step.label = 'Holmes solved it [1].'; }"
                + "        steps.push(step);"
                + "      });"
                + "      body.steps = steps;"
                + "      return new Response(JSON.stringify(body),"
                + "          { status: 200, headers: { 'Content-Type': 'application/json' } });"
                + "    });"
                + "  });"
                + "}");

        page.locator("label.mode-choice-option:has(input[value='DRIFT'])").click();
        page.locator("#chat-input").fill("Tell me about Holmes.");
        page.locator("#chat-form .send-button").click();

        Locator replayCta = page.locator(".replay-cta");
        assertThat(replayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        replayCta.click();

        assertThat(page.locator("#drift-tree")).isVisible();
        List<Map<String, Object>> traceSteps = fetchTraceSteps();
        org.assertj.core.api.Assertions.assertThat(traceSteps.stream().map(step -> step.get("kind")))
                .contains("TEXT_UNIT");
        int branchCount = ((Number) readDriftTreeState().get("branchCount")).intValue();
        org.assertj.core.api.Assertions.assertThat(page.locator(".drift-tree-final").getAttribute("title"))
                .contains("One answer is written");

        Locator caption = page.locator("#replay-caption");
        Locator stepForward = page.locator("#replay-step-forward");
        advanceUntilCaptionContains(caption, stepForward, "Read passage", traceSteps.size());
        Map<String, Object> passageState = readDriftTreeState();
        org.assertj.core.api.Assertions.assertThat(passageState.get("currentBranch")).isEqualTo(0);
        org.assertj.core.api.Assertions.assertThat(passageState.get("finalCurrent")).isEqualTo(false);
        assertThat(page.locator("#replay-hint")).containsText("reads a source passage");

        advanceUntilCaptionContains(caption, stepForward, "synthesized answer", traceSteps.size());
        Map<String, Object> synthesisState = readDriftTreeState();
        org.assertj.core.api.Assertions.assertThat(synthesisState.get("finalCurrent")).isEqualTo(true);
        org.assertj.core.api.Assertions.assertThat(synthesisState.get("resolvedBranches")).isEqualTo(branchCount);
        org.assertj.core.api.Assertions.assertThat(synthesisState.get("currentBranch")).isEqualTo(-1);
        assertThat(page.locator("#replay-hint")).containsText("One answer is written");

        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @Test
    void theDriftPaneCanBeHiddenAndShownAgainFromTheReplayBar() {
        loadDemoDatasetAndWaitReady();

        page.locator("label.mode-choice-option:has(input[value='DRIFT'])").click();
        page.locator("#chat-input").fill("Tell me about Holmes.");
        page.locator("#chat-form .send-button").click();

        Locator replayCta = page.locator(".replay-cta");
        assertThat(replayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        replayCta.click();

        Locator pane = page.locator("#drift-pane");
        Locator toggle = page.locator("#replay-drift-toggle");
        assertThat(pane).isVisible();
        // Docked beside the graph (as the top section of the Retrieval Trace
        // pane), not painted over it.
        org.assertj.core.api.Assertions.assertThat(page.locator("#drift-pane").evaluate(
                "el => el.parentElement.id === 'trace-pane'"
                        + " && el.parentElement.parentElement.classList.contains('canvas')")).isEqualTo(true);
        assertThat(toggle).hasAttribute("aria-pressed", "true");

        page.locator("#drift-pane-close").click();
        assertThat(pane).isHidden();
        assertThat(toggle).isVisible();
        assertThat(toggle).hasAttribute("aria-pressed", "false");

        toggle.click();
        assertThat(pane).isVisible();
        assertThat(toggle).hasAttribute("aria-pressed", "true");

        page.locator("#replay-close").click();
        assertThat(pane).isHidden();
    }

    @Test
    void localReplayKeepsTheDriftTreeHidden() {
        loadDemoDatasetAndWaitReady();

        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();

        Locator replayCta = page.locator(".replay-cta");
        assertThat(replayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        replayCta.click();

        assertThat(page.locator("#drift-tree")).isHidden();
        assertThat(page.locator(".drift-tree-branch")).hasCount(0);
    }

    @Test
    void failedTraceFetchClearsAnyPreviouslyRenderedDriftTree() {
        loadDemoDatasetAndWaitReady();

        page.locator("label.mode-choice-option:has(input[value='DRIFT'])").click();
        page.locator("#chat-input").fill("Tell me about Holmes.");
        page.locator("#chat-form .send-button").click();

        Locator replayCta = page.locator(".replay-cta");
        assertThat(replayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        replayCta.click();

        Locator tree = page.locator("#drift-tree");
        assertThat(tree).isVisible();
        page.locator("#replay-close").click();
        assertThat(tree).isHidden();

        page.route("**/api/traces/**", (Route route) -> route.fulfill(
                new Route.FulfillOptions().setStatus(404).setContentType("application/json").setBody("{}")));
        replayCta.click();

        assertThat(page.locator("#replay-caption")).containsText("could not be loaded");
        assertThat(tree).isHidden();
        assertThat(page.locator(".drift-tree-branch")).hasCount(0);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchTraceSteps() {
        return (List<Map<String, Object>>) page.evaluate(
                "() => fetch('/api/traces/' + document.querySelector('.replay-cta').dataset.traceId)"
                        + "  .then(response => response.json())"
                        + "  .then(body => body.steps || [])");
    }

    private void advanceUntilCaptionContains(Locator caption, Locator stepForward, String expected, int stepBudget) {
        for (int i = 0; i < stepBudget && !caption.textContent().contains(expected); i++) {
            if (Boolean.TRUE.equals(stepForward.isDisabled())) {
                break;
            }
            stepForward.click();
        }
        org.assertj.core.api.Assertions.assertThat(caption.textContent()).contains(expected);
    }

    private void assertBranchZeroFirstEntityStillHighlightsOnCanvas(List<Map<String, Object>> traceSteps) {
        int spawnedIndex = -1;
        for (int i = 0; i < traceSteps.size(); i++) {
            if ("SUB_QUESTION_SPAWNED".equals(traceSteps.get(i).get("kind"))) {
                spawnedIndex = i;
                break;
            }
        }
        org.assertj.core.api.Assertions.assertThat(spawnedIndex).isGreaterThanOrEqualTo(0);
        Map<String, Object> entityStep = traceSteps.get(spawnedIndex + 1);
        org.assertj.core.api.Assertions.assertThat(entityStep.get("kind")).isEqualTo("ENTITY");
        String identifier = (String) entityStep.get("identifier");

        Boolean isHighlighted = (Boolean) page.evaluate(
                "id => window.GraphCanvas.elementHasClass(id, 'step-active')", identifier);
        org.assertj.core.api.Assertions.assertThat(isHighlighted).isTrue();
        assertThat(page.locator("#drift-tree")).isVisible();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDriftTreeState() {
        return (Map<String, Object>) page.evaluate(
                "() => {"
                        + "  const tree = document.getElementById('drift-tree');"
                        + "  const branches = Array.from(tree.querySelectorAll('.drift-tree-branch'));"
                        + "  return {"
                        + "    branchCount: branches.length,"
                        + "    rootCurrent: tree.querySelector('.drift-tree-root')?.classList.contains('is-current') ?? false,"
                        + "    currentBranch: branches.findIndex(branch => branch.classList.contains('is-current')),"
                        + "    resolvedBranches: branches.filter(branch => branch.classList.contains('is-resolved')).length,"
                        + "    upcomingBranches: branches.filter(branch => branch.classList.contains('is-upcoming')).length,"
                        + "    finalCurrent: tree.querySelector('.drift-tree-final')?.classList.contains('is-current') ?? false"
                        + "  };"
                        + "}");
    }

    private void assertSpawnOrSynthesisDoesNotHighlightCommunityNode(String kind) {
        Boolean communityActive = (Boolean) page.evaluate(
                "targetKind => fetch('/api/traces/' + document.querySelector('.replay-cta').dataset.traceId)"
                        + "  .then(response => response.json())"
                        + "  .then(body => {"
                        + "    const step = (body.steps || []).find(entry => entry.kind === targetKind);"
                        + "    if (!step || !step.identifier) {"
                        + "      return false;"
                        + "    }"
                        + "    return window.GraphCanvas.elementHasClass('community::' + step.identifier, 'step-active');"
                        + "  })",
                kind);

        org.assertj.core.api.Assertions.assertThat(communityActive).isFalse();
    }
}
