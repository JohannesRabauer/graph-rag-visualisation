package dev.rabauer.graphrag.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Story 15.4: a cited answer's {@code [n]} markers and Sources list open the
 * cited passage, and Replay names a passage step and highlights the Entities
 * citing it. CI runs without an OpenAI key, so the demo's templated answer is
 * rewritten in the page into a cited one, and its trace gets a TEXT_UNIT step
 * for a real demo passage (read from the graph's entity sources).
 */
class CitationsUiTest extends UiTestSupport {

    private static final String CITED_ANSWER = "Holmes met Adler [1] in London [2]. Both [1, 2]. Not [3].";

    @Test
    @SuppressWarnings("unchecked")
    void citedAnswerOpensPassagesAndReplayNamesThePassageAndHighlightsCitingEntities() {
        List<String> pageErrors = new ArrayList<>();
        page.onPageError(pageErrors::add);
        Fixture fixture = loadDemoWithCitedAnswers();
        String firstId = fixture.firstId();
        String firstDoc = fixture.firstDoc();
        String secondDoc = fixture.secondDoc();
        int firstOrdinal = fixture.firstOrdinal();
        Map<String, Object> firstPassage = fixture.firstPassage();
        Map<String, Object> secondPassage = fixture.secondPassage();
        List<String> firstPassageRequests = fixture.firstPassageRequests();

        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();

        Locator answer = page.locator(".message.answer:not(.pending)").last();
        assertThat(answer.locator(".replay-cta"))
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));

        // Markers: [1] [2] and [1, 2] become buttons; [3] has no citation and stays text.
        Locator markers = answer.locator(".citation-marker");
        assertThat(markers).hasCount(4);
        assertThat(markers.nth(0)).hasText("1");
        assertThat(markers.nth(0)).hasAttribute("aria-label", "Source 1: " + firstDoc);
        assertThat(markers.nth(1)).hasAttribute("aria-label", "Source 2: " + secondDoc);
        assertThat(markers.nth(2)).hasText("1");
        assertThat(markers.nth(3)).hasText("2");
        org.assertj.core.api.Assertions.assertThat(answer.locator(":scope > span").textContent())
                .isEqualTo(CITED_ANSWER);

        Locator rows = answer.locator(".answer-sources .answer-source-toggle");
        assertThat(answer.locator(".answer-sources-heading")).hasText("Sources");
        assertThat(rows).hasCount(2);
        assertThat(rows.nth(0)).hasText("1. " + firstDoc + " · First excerpt.");
        assertThat(rows.nth(1)).hasText("2. " + secondDoc + " · Second excerpt.");

        Locator panel = answer.locator(".answer-passage");
        assertThat(panel).isHidden();
        markers.nth(0).click();
        assertThat(panel).isVisible();
        assertThat(panel.locator(".answer-passage-text")).hasText((String) firstPassage.get("text"));
        assertThat(markers.nth(0)).hasAttribute("aria-expanded", "true");
        String panelId = panel.getAttribute("id");
        org.assertj.core.api.Assertions.assertThat(panelId).isNotBlank();
        assertThat(markers.nth(0)).hasAttribute("aria-controls", panelId);
        assertThat(rows.nth(1)).hasAttribute("aria-controls", panelId);
        assertThat(panel.locator(".answer-passage-text")).hasAttribute("aria-live", "polite");

        // Switching citations: row 2 replaces passage 1 in the same panel.
        rows.nth(1).click();
        assertThat(panel).isVisible();
        assertThat(panel.locator(".answer-passage-title")).hasText("2. " + secondDoc);
        assertThat(panel.locator(".answer-passage-text")).hasText((String) secondPassage.get("text"));
        assertThat(markers.nth(0)).hasAttribute("aria-expanded", "false");
        assertThat(rows.nth(0)).hasAttribute("aria-expanded", "false");
        assertThat(markers.nth(1)).hasAttribute("aria-expanded", "true");
        assertThat(rows.nth(1)).hasAttribute("aria-expanded", "true");

        rows.nth(0).click();
        assertThat(panel.locator(".answer-passage-title")).hasText("1. " + firstDoc);
        assertThat(panel.locator(".answer-passage-text")).hasText((String) firstPassage.get("text"));
        rows.nth(0).click();
        assertThat(panel).isHidden();
        rows.nth(0).click();
        assertThat(panel).isVisible();
        assertThat(panel.locator(".answer-passage-text")).hasText((String) firstPassage.get("text"));

        // Replay: the first step is the TEXT_UNIT step for the first citation.
        answer.locator(".replay-cta").click();
        Locator caption = page.locator("#replay-caption");
        assertThat(caption).containsText(
                "Read passage " + (firstOrdinal + 1) + " of " + firstDoc + ": First excerpt.");

        List<String> citingIds = (List<String>) page.evaluate(
                "id => window.GraphCanvas.citingEntityIds(id)", firstId);
        org.assertj.core.api.Assertions.assertThat(citingIds).isNotEmpty();
        boolean anyHighlighted = false;
        for (String id : citingIds) {
            if (Boolean.TRUE.equals(page.evaluate(
                    "id => window.GraphCanvas.elementHasClass(id, 'step-active')", id))) {
                anyHighlighted = true;
            }
        }
        org.assertj.core.api.Assertions.assertThat(anyHighlighted).isTrue();

        // Stepping off the passage step uses the ordinary caption again.
        page.locator("#replay-step-forward").click();
        assertThat(caption).not().containsText("Read passage");
        // The passage step leaves no trailing ring on its citing Entities.
        List<String> targeted = (List<String>) page.evaluate(
                "() => fetch('/api/traces/' + document.querySelector('.replay-cta').dataset.traceId)"
                        + "  .then(response => response.json())"
                        + "  .then(body => {"
                        + "    const step = body.steps[1];"
                        + "    const ids = [step.identifier];"
                        + "    if (step.kind === 'RELATIONSHIP') {"
                        + "      const parts = String(step.identifier).split('->');"
                        + "      ids.push(parts[0], parts[2]);"
                        + "    }"
                        + "    return ids;"
                        + "  })");
        for (String id : citingIds) {
            if (!targeted.contains(id)) {
                org.assertj.core.api.Assertions.assertThat(page.evaluate(
                        "id => window.GraphCanvas.elementHasClass(id, 'step-previous')", id)).isEqualTo(false);
            }
        }
        page.locator("#replay-close").click();

        // Passage fetched once: the chat panel and Replay share the cache.
        org.assertj.core.api.Assertions.assertThat(firstPassageRequests).hasSize(1);

        // An answer without citations renders as plain text, with no Sources list.
        page.evaluate("() => { window.__citeNext = false; }");
        int answersBefore = page.locator(".message.answer:not(.pending)").count();
        Response plainResponse = page.waitForResponse(
                response -> response.url().endsWith("/query"),
                () -> {
                    page.locator("#chat-input").fill("Who is Sherlock Holmes?");
                    page.locator("#chat-form .send-button").click();
                });
        String plainAnswer = (String) ((Map<String, Object>) page.evaluate(
                "text => JSON.parse(text)", plainResponse.text())).get("answer");
        assertThat(page.locator(".message.answer:not(.pending)")).hasCount(answersBefore + 1);
        Locator plain = page.locator(".message.answer:not(.pending)").last();
        assertThat(plain.locator(".replay-cta")).isVisible();
        assertThat(plain.locator(".answer-sources")).hasCount(0);
        assertThat(plain.locator(".answer-passage")).hasCount(0);
        assertThat(plain.locator(".citation-marker")).hasCount(0);
        assertThat(plain.locator(":scope > span")).hasCount(1);
        org.assertj.core.api.Assertions.assertThat(plain.locator(":scope > span").evaluate("el => el.childNodes.length"))
                .isEqualTo(1);
        if (plainAnswer != null) {
            org.assertj.core.api.Assertions.assertThat(plain.locator(":scope > span").textContent())
                    .isEqualTo(plainAnswer);
        }

        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @Test
    void replayCaptionFallsBackWhenThePassageCannotBeLoaded() {
        loadDemoDatasetAndWaitReady();
        page.route("**/api/corpora/*/text-units/**", route -> route.fulfill(
                new com.microsoft.playwright.Route.FulfillOptions()
                        .setStatus(404).setContentType("application/json").setBody("{}")));
        page.evaluate("() => {"
                + "  const originalFetch = window.fetch;"
                + "  window.fetch = (input, init) => originalFetch(input, init).then(response => {"
                + "    const url = typeof input === 'string' ? input : input.url;"
                + "    if (!response.ok) { return response; }"
                + "    if (url.endsWith('/query')) {"
                + "      return response.json().then(body => {"
                + "        body.answer = 'Holmes [1].';"
                + "        body.citations = [{ textUnitId: 'tu-gone', documentName: 'gone.txt', excerpt: 'Gone.' }];"
                + "        return new Response(JSON.stringify(body),"
                + "            { status: 200, headers: { 'Content-Type': 'application/json' } });"
                + "      });"
                + "    }"
                + "    if (url.includes('/api/traces/')) {"
                + "      return response.json().then(body => {"
                + "        body.steps = [{ kind: 'TEXT_UNIT', identifier: 'tu-gone', label: 'Gone.' }]"
                + "            .concat(body.steps || []);"
                + "        return new Response(JSON.stringify(body),"
                + "            { status: 200, headers: { 'Content-Type': 'application/json' } });"
                + "      });"
                + "    }"
                + "    return response;"
                + "  });"
                + "}");

        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();
        Locator answer = page.locator(".message.answer:not(.pending)").last();
        assertThat(answer.locator(".replay-cta"))
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));

        answer.locator(".citation-marker").first().click();
        assertThat(answer.locator(".answer-passage-text")).hasText("Passage not available");

        page.waitForResponse(
                response -> response.url().contains("/text-units/") && response.status() == 404,
                () -> answer.locator(".replay-cta").click());
        Locator caption = page.locator("#replay-caption");
        assertThat(caption).containsText("Read passage: Gone.");
    }

    @Test
    void replayCaptionNamesAPassageThatWasNotOpenedInTheChatFirst() {
        Fixture fixture = loadDemoWithCitedAnswers();
        page.locator("#chat-input").fill("Tell me about Irene Adler.");
        page.locator("#chat-form .send-button").click();
        Locator answer = page.locator(".message.answer:not(.pending)").last();
        assertThat(answer.locator(".replay-cta"))
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));

        answer.locator(".replay-cta").click();
        assertThat(page.locator("#replay-caption")).containsText(
                "Read passage " + (fixture.firstOrdinal() + 1) + " of " + fixture.firstDoc() + ": First excerpt.");
    }

    @Test
    void entitiesRestoredFromTheGraphEndpointKnowTheirCitedPassages() {
        Fixture fixture = loadDemoWithCitedAnswers();
        page.waitForResponse(
                response -> response.url().endsWith("/api/corpora/" + fixture.corpusId() + "/graph"),
                () -> page.reload());
        assertThat(page.locator("#workflow-status-text"))
                .containsText("Ready", new LocatorAssertions.ContainsTextOptions().setTimeout(20000));
        page.waitForFunction("id => window.GraphCanvas.citingEntityIds(id).length > 0", fixture.firstId(),
                new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(20000));
    }

    private record Fixture(String corpusId, String firstId, String secondId,
                           Map<String, Object> firstPassage, Map<String, Object> secondPassage,
                           List<String> firstPassageRequests) {
        String firstDoc() { return (String) firstPassage.get("documentName"); }
        String secondDoc() { return (String) secondPassage.get("documentName"); }
        int firstOrdinal() { return ((Number) firstPassage.get("ordinal")).intValue(); }
    }

    /**
     * Loads the demo dataset, picks two distinct real passages from the graph's
     * entity sources and rewrites the query/trace responses into a cited answer
     * whose trace starts with a TEXT_UNIT step for the first passage.
     */
    @SuppressWarnings("unchecked")
    private Fixture loadDemoWithCitedAnswers() {
        page.navigate(baseUrl() + "/");
        Response demoResponse = page.waitForResponse(
                response -> response.url().endsWith("/api/corpora/demo"),
                () -> page.locator("#demo-dataset-button").click());
        String corpusId = demoResponse.text().replaceAll(".*\"corpusId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        assertThat(page.locator("#workflow-status-text"))
                .containsText("Ready", new LocatorAssertions.ContainsTextOptions().setTimeout(20000));

        List<String> sourceIds = (List<String>) page.evaluate(
                "corpusId => fetch('/api/corpora/' + encodeURIComponent(corpusId) + '/graph')"
                        + "  .then(response => response.json())"
                        + "  .then(body => {"
                        + "    const ids = [];"
                        + "    (body.entities || []).forEach(entity => (entity.sources || []).forEach(source => {"
                        + "      if (source.textUnitId && !ids.includes(source.textUnitId)) { ids.push(source.textUnitId); }"
                        + "    }));"
                        + "    return ids;"
                        + "  })",
                corpusId);
        // Switching between two citations needs two distinct passages; fail rather than fall back.
        org.assertj.core.api.Assertions.assertThat(sourceIds).hasSizeGreaterThanOrEqualTo(2);
        String firstId = sourceIds.get(0);
        String secondId = sourceIds.get(1);
        Map<String, Object> firstPassage = fetchPassage(corpusId, firstId);
        Map<String, Object> secondPassage = fetchPassage(corpusId, secondId);
        String firstDoc = (String) firstPassage.get("documentName");
        String secondDoc = (String) secondPassage.get("documentName");

        List<String> firstPassageRequests = new CopyOnWriteArrayList<>();
        page.onRequest(request -> {
            String url = java.net.URLDecoder.decode(request.url(), java.nio.charset.StandardCharsets.UTF_8);
            if (url.contains("/text-units/") && url.endsWith("/" + firstId)) {
                firstPassageRequests.add(request.url());
            }
        });

        page.evaluate("args => {"
                + "  window.__citeNext = true;"
                + "  const originalFetch = window.fetch;"
                + "  const json = body => new Response(JSON.stringify(body),"
                + "      { status: 200, headers: { 'Content-Type': 'application/json' } });"
                + "  window.fetch = (input, init) => originalFetch(input, init).then(response => {"
                + "    const url = typeof input === 'string' ? input : input.url;"
                + "    if (!window.__citeNext || !response.ok) { return response; }"
                + "    if (url.endsWith('/query')) {"
                + "      return response.json().then(body => {"
                + "        body.answer = args.answer;"
                + "        body.citations = ["
                + "          { textUnitId: args.firstId, documentName: args.firstDoc, excerpt: 'First excerpt.' },"
                + "          { textUnitId: args.secondId, documentName: args.secondDoc, excerpt: 'Second excerpt.' }];"
                + "        body.traceStepCount = (body.traceStepCount || 0) + 1;"
                + "        return json(body);"
                + "      });"
                + "    }"
                + "    if (url.includes('/api/traces/')) {"
                + "      return response.json().then(body => {"
                + "        body.steps = [{ kind: 'TEXT_UNIT', identifier: args.firstId, label: 'First excerpt.' }]"
                + "            .concat(body.steps || []);"
                + "        return json(body);"
                + "      });"
                + "    }"
                + "    return response;"
                + "  });"
                + "}",
                Map.of("answer", CITED_ANSWER, "firstId", firstId, "secondId", secondId,
                        "firstDoc", firstDoc, "secondDoc", secondDoc));
        return new Fixture(corpusId, firstId, secondId, firstPassage, secondPassage, firstPassageRequests);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchPassage(String corpusId, String textUnitId) {
        return (Map<String, Object>) page.evaluate(
                "args => fetch('/api/corpora/' + encodeURIComponent(args.corpusId) + '/text-units/'"
                        + "    + encodeURIComponent(args.id)).then(response => response.json())",
                Map.of("corpusId", corpusId, "id", textUnitId));
    }
}
