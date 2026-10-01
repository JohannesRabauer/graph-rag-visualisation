package dev.rabauer.graphrag.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/** Playwright coverage for the docked contextual help pane and its live layer. */
class HelpPaneUiTest extends UiTestSupport {

    private static final LocatorAssertions.ContainsTextOptions SLOW =
            new LocatorAssertions.ContainsTextOptions().setTimeout(20000);

    private static final List<String> TOPICS = List.of(
            "upload", "demo-offline", "ingestion-progress", "corpus-chip-history", "kg-overview",
            "entity-types", "communities", "entity-detail", "mode-chooser", "local-search",
            "global-search", "drift-search", "reading-an-answer", "trace-replay", "drift-tree",
            "vector-vs-graphrag");

    private void ask(String mode, String question) {
        page.locator("label.mode-choice-option:has(input[value='" + mode + "'])").click();
        page.locator("#chat-input").fill(question);
        page.locator("#chat-form .send-button").click();
        assertThat(page.locator(".message.answer").last()).isVisible(
                new com.microsoft.playwright.assertions.LocatorAssertions.IsVisibleOptions().setTimeout(20000));
    }

    private void openHelp(String topic) {
        page.evaluate("t => window.Help.open(t)", topic);
        assertThat(page.locator("#help-pane")).isVisible();
        assertThat(page.locator("#help-pane-content article[data-topic='" + topic + "']")).isVisible();
    }

    @Test
    void opensSwapsAndClosesWithEscapeReturningFocus() {
        loadDemoDatasetAndWaitReady();
        Locator chooserHelp = page.locator("button[data-help='mode-chooser']");
        chooserHelp.click();
        assertThat(page.locator("#help-pane")).isVisible();
        assertThat(page.locator("#help-pane-content article[data-topic='mode-chooser']")).isVisible();

        page.locator("button[data-help='kg-overview']").click();
        assertThat(page.locator("#help-pane-content article[data-topic='kg-overview']")).isVisible();
        assertThat(page.locator("#help-pane-content article[data-topic='mode-chooser']")).hasCount(0);

        page.locator("button[data-help='mode-chooser']").click();
        page.keyboard().press("Escape");
        assertThat(page.locator("#help-pane")).isHidden();
        assertThat(chooserHelp).isFocused();
    }

    @Test
    void closeButtonDismissesThePane() {
        page.navigate(baseUrl() + "/");
        page.locator("button[data-help='upload']").click();
        assertThat(page.locator("#help-pane")).isVisible();
        page.locator("#help-pane-close").click();
        assertThat(page.locator("#help-pane")).isHidden();
    }

    @Test
    void theGraphCanvasShrinksWhilePaneIsOpenAndBodyTextIsProjectorSized() {
        loadDemoDatasetAndWaitReady();
        Number before = (Number) page.evaluate("() => window.GraphCanvas.dimensions().width");
        openHelp("mode-chooser");
        page.waitForFunction("b => window.GraphCanvas.dimensions().width < b", before);
        Number fontSize = (Number) page.evaluate(
                "() => parseFloat(getComputedStyle(document.querySelector('#help-pane-content .help-abstract')).fontSize)");
        assertThat(fontSize.doubleValue()).isGreaterThanOrEqualTo(16.0);
        page.keyboard().press("Escape");
        page.waitForFunction("b => window.GraphCanvas.dimensions().width >= b - 2", before);
    }

    @Test
    void liveLayerShowsAnEmptyStateThenLocalGlobalAndDriftPathsAfterAnAnswer() {
        loadDemoDatasetAndWaitReady();
        openHelp("local-search");
        assertThat(page.locator("#help-pane [data-live]")).containsText("Ask a question", SLOW);

        ask("LOCAL", "Who is Sherlock Holmes?");
        assertThat(page.locator("#help-pane [data-live][data-live-state='ready']")).isVisible();
        assertThat(page.locator("#help-pane .help-live-captions")).containsText("Seed entity:");

        openHelp("global-search");
        assertThat(page.locator("#help-pane [data-live]")).containsText("Global Search", SLOW);
        ask("GLOBAL", "Irene Adler");
        assertThat(page.locator("#help-pane [data-live][data-live-state='ready']")).isVisible();
        assertThat(page.locator("#help-pane [data-live] .hs-box--best")).hasCount(1);

        openHelp("drift-search");
        assertThat(page.locator("#help-pane [data-live]")).containsText("Drift Search", SLOW);
        ask("DRIFT", "Sherlock Holmes");
        assertThat(page.locator("#help-pane [data-live][data-live-state='ready']")).isVisible();
        assertThat(page.locator("#help-pane [data-live]")).containsText("Communities read");
    }

    /**
     * Story 15.3: a synthesized Global trace reads TEXT_UNIT passages and its
     * answer quotes no summary. The demo runs offline, so the query response
     * and the trace are rewritten in the page into that shape.
     */
    @Test
    void liveGlobalLayerMarksTheFirstCommunityOfASynthesizedTrace() {
        loadDemoDatasetAndWaitReady();
        page.evaluate("() => {"
                + "  const originalFetch = window.fetch;"
                + "  const rewrite = (response, change) => response.json().then(body => {"
                + "    change(body);"
                + "    return new Response(JSON.stringify(body),"
                + "        { status: 200, headers: { 'Content-Type': 'application/json' } });"
                + "  });"
                + "  window.fetch = (input, init) => originalFetch(input, init).then(response => {"
                + "    const url = typeof input === 'string' ? input : input.url;"
                + "    if (!response.ok) { return response; }"
                + "    if (url.includes('/api/traces/')) {"
                + "      return rewrite(response, body => {"
                + "        const steps = [];"
                + "        (body.steps || []).forEach(step => {"
                + "          steps.push(step);"
                + "          if (step.kind === 'COMMUNITY') {"
                + "            steps.push({ kind: 'TEXT_UNIT', identifier: 'tu-' + steps.length, label: 'A passage.' });"
                + "          }"
                + "        });"
                + "        body.steps = steps;"
                + "      });"
                + "    }"
                + "    if (url.includes('/query')) {"
                + "      return rewrite(response, body => {"
                + "        if (body.answer) { body.answer = 'A written answer that quotes no summary [1].'; }"
                + "      });"
                + "    }"
                + "    return response;"
                + "  });"
                + "}");

        openHelp("global-search");
        assertThat(page.locator("#help-pane [data-live]")).containsText("Ask a question", SLOW);
        ask("GLOBAL", "Irene Adler");
        assertThat(page.locator("#help-pane [data-live][data-live-state='ready']")).isVisible();
        assertThat(page.locator(".message.answer").last())
                .containsText("A written answer that quotes no summary [1].");

        assertThat(page.locator("#help-pane [data-live] .hs-box--best")).hasCount(1);
        String firstCommunityLabel = (String) page.evaluate(
                "() => fetch('/api/traces/' + document.querySelector('.replay-cta').dataset.traceId)"
                        + "  .then(response => response.json())"
                        + "  .then(body => body.steps.find(step => step.kind === 'COMMUNITY').label)");
        String bestTitle = (String) page.evaluate("() => document.querySelector("
                + "'#help-pane [data-live] .hs-box--best').parentElement.querySelector('title').textContent");
        assertThat(bestTitle).isEqualTo("Best match (closest match, read first): " + firstCommunityLabel);
        assertThat(page.locator("#help-pane .help-live-captions"))
                .containsText("the answer was written from these communities and their passages");
    }

    @Test
    void theRealChooserAndSettingsHelpButtonsDoNotDisturbTheirNeighbours() {
        loadDemoDatasetAndWaitReady();
        String modeBefore = (String) page.evaluate(
                "() => document.querySelector(\"input[name='search-mode']:checked\").value");
        page.locator("button[data-help='mode-chooser']").click();
        assertThat(page.locator("#help-pane-content article[data-topic='mode-chooser']")).isVisible();
        assertThat((String) page.evaluate(
                "() => document.querySelector(\"input[name='search-mode']:checked\").value")).isEqualTo(modeBefore);

        Locator toggle = page.locator("[aria-controls='canvas-settings-popover']");
        String expandedBefore = toggle.getAttribute("aria-expanded");
        page.locator("button[data-help='entity-types']").click();
        assertThat(page.locator("#help-pane-content article[data-topic='entity-types']")).isVisible();
        assertThat(toggle.getAttribute("aria-expanded")).isEqualTo(expandedBefore);
    }

    @Test
    void offlineDemoExplainsThereIsNothingToTrace() {
        page.navigate(baseUrl() + "/");
        page.locator("#demo-offline-button").click();
        assertThat(page.locator("#workflow-status-text")).containsText("Ready", SLOW);
        openHelp("local-search");
        assertThat(page.locator("#help-pane [data-live]")).containsText("offline demo", SLOW);
    }

    @Test
    void restartingThroughTheRealButtonResetsTheLiveLayerToItsEmptyState() {
        loadDemoDatasetAndWaitReady();
        ask("LOCAL", "Who is Sherlock Holmes?");
        openHelp("local-search");
        assertThat(page.locator("#help-pane [data-live][data-live-state='ready']")).isVisible();

        page.onDialog(dialog -> dialog.accept());
        page.locator("#workflow-restart-button").click();
        assertThat(page.locator("#help-pane [data-live][data-live-state='empty']")).containsText(
                "Ask a question to see this in your data", SLOW);
    }

    @Test
    void switchingCorpusByEventResetsTheLiveLayerToItsEmptyState() {
        loadDemoDatasetAndWaitReady();
        ask("LOCAL", "Who is Sherlock Holmes?");
        openHelp("local-search");
        assertThat(page.locator("#help-pane [data-live][data-live-state='ready']")).isVisible();

        page.evaluate("() => document.dispatchEvent(new CustomEvent('graphrag:corpus',"
                + " {detail: {corpusId: 'another-corpus', offline: false}}))");
        assertThat(page.locator("#help-pane [data-live]")).containsText("Ask a question", SLOW);
    }

    @Test
    void helpButtonsDoNotOverlapEachOtherOrTheReplayControls() {
        loadDemoDatasetAndWaitReady();
        ask("LOCAL", "Who is Sherlock Holmes?");
        page.locator(".replay-cta").last().click();
        assertThat(page.locator("#replay-scrubber")).isVisible();
        @SuppressWarnings("unchecked")
        List<String> overlaps = (List<String>) page.evaluate("() => {"
                + " const vis = el => { const r = el.getBoundingClientRect();"
                + "   return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden' && el.offsetParent !== null; };"
                + " const name = el => el.id || el.getAttribute('data-help') || el.className;"
                + " const items = [...document.querySelectorAll('.help-btn')]"
                + "   .concat(['replay-close','replay-play-pause','replay-step-back','replay-step-forward']"
                + "     .map(id => document.getElementById(id)))"
                + "   .filter(el => el && vis(el));"
                + " const out = [];"
                + " for (let i = 0; i < items.length; i++) for (let j = i + 1; j < items.length; j++) {"
                + "   const a = items[i].getBoundingClientRect(), b = items[j].getBoundingClientRect();"
                + "   const isHelpPair = items[i].classList.contains('help-btn') || items[j].classList.contains('help-btn');"
                + "   if (isHelpPair && a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom)"
                + "     out.push(name(items[i]) + ' x ' + name(items[j])); }"
                + " return out; }");
        assertThat(overlaps).isEmpty();
    }

    @Test
    void narrowViewportShowsThePaneAsAnOverlayWithAVisibleCloseButton() {
        page.setViewportSize(800, 900);
        page.navigate(baseUrl() + "/");
        page.locator("button[data-help='upload']").click();
        assertThat(page.locator("#help-pane")).isVisible();
        assertThat(page.locator("#help-pane")).hasCSS("position", "absolute");
        assertThat(page.locator("#help-pane-close")).isVisible();
        page.locator("#help-pane-close").click();
        assertThat(page.locator("#help-pane")).isHidden();
    }

    @Test
    void everyTopicOpens() {
        loadDemoDatasetAndWaitReady();

        int buttons = page.locator("[data-help]").count();
        for (int i = 0; i < buttons; i++) {
            Locator button = page.locator("[data-help]").nth(i);
            String label = button.getAttribute("aria-label");
            assertThat(label == null ? "" : label.trim()).isNotEmpty();
            if (button.isVisible()) {
                String topic = button.getAttribute("data-help");
                button.click();
                assertThat(page.locator("#help-pane-content article[data-topic='" + topic + "']")).isVisible();
                assertThat((String) page.evaluate("() => window.Help.currentTopic()")).isEqualTo(topic);
            }
        }

        for (String topic : TOPICS) {
            openHelp(topic);
            assertThat(page.locator("#help-pane-content h2")).isVisible();
            assertThat(page.locator("#help-pane-content svg.help-svg").first()).isVisible();
        }
    }
}
