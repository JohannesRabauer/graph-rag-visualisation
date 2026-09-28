package com.graphraglens.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Real-browser coverage for the Entity-type color-coding toggle
 * (spec-10-3, GitHub #22): the Tag chip and matching canvas node color by
 * distinct {@code type} value, deterministically, and the toggle reverts
 * both to the flat neutral style live. Node interactions go through the
 * same {@code GraphCanvas.simulateTap} test hook
 * {@link MainScreenDetailPanelUiTest} already establishes.
 */
class EntityTypeColorToggleUiTest extends UiTestSupport {

    // spec-11-7 (#36): the toggle is no longer permanently rendered — it
    // now nests inside the settings popover behind `#canvas-settings-toggle`
    // (its own id/semantics are unchanged, only its container). Opens it so
    // the checkbox is actually interactable/visible before these tests
    // exercise it.
    private void openSettingsPopover() {
        page.locator("#canvas-settings-toggle").click();
    }

    private void tap(String identity) {
        boolean fired = (boolean) page.evaluate(
                "identity => window.GraphCanvas.simulateTap(identity)", identity);
        org.assertj.core.api.Assertions.assertThat(fired)
                .withFailMessage("Node '%s' was not found on the rendered graph.", identity)
                .isTrue();
    }

    // The DOM's own `getComputedStyle` and Cytoscape's `node.style()` format
    // an identical color differently ("rgb(95, 115, 133)" vs.
    // "rgb(95,115,133)") — stripping whitespace before comparing lets the
    // two be checked for the same underlying color without depending on
    // either side's particular formatting.
    private static String normalizeColor(String color) {
        return color == null ? null : color.replace(" ", "");
    }

    private String chipStyle(String property) {
        String raw = (String) page.evaluate(
                "property => { var chip = document.querySelector('#entity-detail-tags .node-detail-tag');"
                        + " return chip ? getComputedStyle(chip)[property] : null; }",
                property);
        return normalizeColor(raw);
    }

    @Test
    void toggleDefaultsCheckedOnAFreshDemoDatasetLoad() {
        loadDemoDatasetAndWaitReady();
        openSettingsPopover();

        Locator toggle = page.locator("#entity-type-color-toggle");
        assertThat(toggle).isChecked();
        assertThat(page.locator("#entity-type-toggle-wrap")).not().isHidden();
    }

    @Test
    void openingAPersonTypedEntityColorsTheChipAndItsCanvasNodeIdentically() {
        loadDemoDatasetAndWaitReady();

        tap("sherlock holmes::person");
        assertThat(page.locator("#entity-detail-type")).containsText("Person");

        String chipBackground = chipStyle("backgroundColor");
        String chipBorder = chipStyle("borderColor");

        String nodeFill = normalizeColor((String) page.evaluate(
                "() => window.GraphCanvas.entityNodeFillColor('sherlock holmes::person')"));

        org.assertj.core.api.Assertions.assertThat(chipBackground).isEqualTo(nodeFill);

        // Border-color is checked against a DIFFERENT, never-tapped
        // Person-typed Entity ("holmes::person") rather than the tapped
        // one itself: tapping a node also gives it the pre-existing
        // keyboard roving-focus ring (`.kbd-focus`, `setKeyboardFocus`),
        // which deliberately overrides a node's rendered border-color
        // regardless of type-coloring, by design/style-array position —
        // unrelated to this toggle. Same type still means same
        // deterministic border color on any node that isn't focused.
        String siblingNodeBorder = normalizeColor((String) page.evaluate(
                "() => window.GraphCanvas.entityNodeBorderColor('holmes::person')"));
        org.assertj.core.api.Assertions.assertThat(chipBorder).isEqualTo(siblingNodeBorder);

        // Neutral-style regression guard: a colored chip/node must not equal
        // the flat neutral `--chrome`/transparent-border fallback style.
        org.assertj.core.api.Assertions.assertThat(chipBackground).isNotEqualTo("rgba(0, 0, 0, 0)");
    }

    @Test
    void uncheckingTheToggleWithThePanelOpenRevertsBothImmediately() {
        loadDemoDatasetAndWaitReady();

        tap("sherlock holmes::person");
        String coloredBackground = chipStyle("backgroundColor");
        String coloredNodeFill = (String) page.evaluate(
                "() => window.GraphCanvas.entityNodeFillColor('sherlock holmes::person')");

        openSettingsPopover();
        page.locator("#entity-type-color-toggle").uncheck();

        String neutralChipBackground = chipStyle("backgroundColor");
        String neutralNodeFill = (String) page.evaluate(
                "() => window.GraphCanvas.entityNodeFillColor('sherlock holmes::person')");

        org.assertj.core.api.Assertions.assertThat(neutralChipBackground).isNotEqualTo(coloredBackground);
        org.assertj.core.api.Assertions.assertThat(neutralNodeFill).isNotEqualTo(coloredNodeFill);

        // Reverted to the base `node` selector's SHARED neutral fill — not
        // just "some other color", but the exact same flat style every
        // uncolored node already renders with (a different, Concept-typed
        // Entity, which would otherwise resolve to a different type color).
        String otherEntityNeutralFill = (String) page.evaluate(
                "() => window.GraphCanvas.entityNodeFillColor('king::concept')");
        org.assertj.core.api.Assertions.assertThat(neutralNodeFill).isEqualTo(otherEntityNeutralFill);
    }

    @Test
    void twoDifferentEntitiesSharingATypeResolveToTheSameColor() {
        loadDemoDatasetAndWaitReady();

        // "sherlock holmes::person" and "holmes::person" both infer to
        // type "Person" (LangChain4jLlmPort.inferType matches "holmes") —
        // two distinct Entity identities sharing one type string.
        String fillA = (String) page.evaluate(
                "() => window.GraphCanvas.entityNodeFillColor('sherlock holmes::person')");
        String fillB = (String) page.evaluate(
                "() => window.GraphCanvas.entityNodeFillColor('holmes::person')");
        String borderA = (String) page.evaluate(
                "() => window.GraphCanvas.entityNodeBorderColor('sherlock holmes::person')");
        String borderB = (String) page.evaluate(
                "() => window.GraphCanvas.entityNodeBorderColor('holmes::person')");

        org.assertj.core.api.Assertions.assertThat(fillA).isNotNull().isEqualTo(fillB);
        org.assertj.core.api.Assertions.assertThat(borderA).isNotNull().isEqualTo(borderB);
    }

    // Runtime check of the `node.type-colored:not(.community-hull)` style
    // selector's precedence — it's positioned before `.step-active`/
    // `.step-previous` in graph-canvas.js's `init` style array so Replay's
    // own border override always wins, exactly like the base `node`
    // selector already does. Reuses DriftTreeReplayUiTest's own
    // trace-fetching/stepping pattern.
    @Test
    void replayingATraceOverridesTheEntityTypeBorderWithTheActiveStepRing() {
        loadDemoDatasetAndWaitReady();

        page.locator("#chat-input").fill("Tell me about Holmes.");
        page.locator("#chat-form .send-button").click();

        Locator replayCta = page.locator(".replay-cta");
        assertThat(replayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));

        List<Map<String, Object>> steps = fetchTraceSteps();
        int entityIndex = -1;
        String identifier = null;
        for (int i = 0; i < steps.size(); i++) {
            if ("ENTITY".equals(steps.get(i).get("kind"))) {
                entityIndex = i;
                identifier = (String) steps.get(i).get("identifier");
                break;
            }
        }
        org.assertj.core.api.Assertions.assertThat(entityIndex)
                .withFailMessage("No ENTITY step found in this trace — cannot verify Replay border precedence.")
                .isGreaterThanOrEqualTo(0);

        // Captured before Replay opens — still the deterministic
        // type-colored border, nothing has highlighted this node yet.
        String typeColoredBorder = normalizeColor((String) page.evaluate(
                "id => window.GraphCanvas.entityNodeBorderColor(id)", identifier));

        replayCta.click();
        // openReplay's trace fetch is async — wait for the scrubber to
        // actually be populated (steps loaded, ticks built) before
        // stepping, otherwise stepForward.click() races an empty step list
        // and silently no-ops.
        assertThat(page.locator("#replay-scrubber")).not().isHidden();
        assertThat(page.locator(".replay-tick")).hasCount(steps.size());

        Locator stepForward = page.locator("#replay-step-forward");
        for (int i = 0; i < entityIndex; i++) {
            stepForward.click();
        }

        Boolean isStepActive = (Boolean) page.evaluate(
                "id => window.GraphCanvas.elementHasClass(id, 'step-active')", identifier);
        org.assertj.core.api.Assertions.assertThat(isStepActive).isTrue();

        String activeBorder = normalizeColor((String) page.evaluate(
                "id => window.GraphCanvas.entityNodeBorderColor(id)", identifier));

        // Resolves to the active ring color (--active: #E85D2B), not the
        // entity's own deterministic type border, while it's the current
        // Replay step.
        org.assertj.core.api.Assertions.assertThat(activeBorder).isEqualTo(normalizeColor("rgb(232, 93, 43)"));
        org.assertj.core.api.Assertions.assertThat(activeBorder).isNotEqualTo(typeColoredBorder);
    }

    // The spec's own AC: reached via keyboard alone (Tab/Space), fully
    // operable without a mouse. A real, unmodified `<input
    // type="checkbox">` is already keyboard-focusable/-activatable by
    // construction, but this exercises that at runtime rather than by
    // markup inspection alone.
    @Test
    void toggleIsFullyOperableViaKeyboardAlone() {
        loadDemoDatasetAndWaitReady();

        tap("sherlock holmes::person");
        String coloredChipBackground = chipStyle("backgroundColor");
        String coloredNodeFill = normalizeColor((String) page.evaluate(
                "() => window.GraphCanvas.entityNodeFillColor('sherlock holmes::person')"));

        openSettingsPopover();
        // The community-visualization toggle's checkbox sits immediately
        // before this one in the DOM (both are lone-input labels inside
        // the settings popover) — clicking it establishes a known
        // keyboard-focus starting point without depending on the whole
        // page's full Tab order, then a single real Tab key press moves
        // focus onto the entity-type toggle exactly as a keyboard-only
        // user tabbing through this control group would.
        page.locator("#community-visualization-toggle").click();
        page.keyboard().press("Tab");

        String focusedId = (String) page.evaluate("() => document.activeElement ? document.activeElement.id : null");
        org.assertj.core.api.Assertions.assertThat(focusedId).isEqualTo("entity-type-color-toggle");

        Locator toggle = page.locator("#entity-type-color-toggle");
        assertThat(toggle).isChecked();

        page.keyboard().press("Space");

        assertThat(toggle).not().isChecked();

        String neutralChipBackground = chipStyle("backgroundColor");
        String neutralNodeFill = normalizeColor((String) page.evaluate(
                "() => window.GraphCanvas.entityNodeFillColor('sherlock holmes::person')"));

        org.assertj.core.api.Assertions.assertThat(neutralChipBackground).isNotEqualTo(coloredChipBackground);
        org.assertj.core.api.Assertions.assertThat(neutralNodeFill).isNotEqualTo(coloredNodeFill);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchTraceSteps() {
        return (List<Map<String, Object>>) page.evaluate(
                "() => fetch('/api/traces/' + document.querySelector('.replay-cta').dataset.traceId)"
                        + "  .then(response => response.json())"
                        + "  .then(body => body.steps || [])");
    }
}
