package dev.rabauer.graphrag.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * The Compare tab: both answers side by side with their sources, key figures
 * per side, the vector side's similarity ranking, the verdict with its label,
 * a Replay button per side (the vector replay runs on the Compare tab itself),
 * no extra chat message, and keyboard navigation over the two tabs. CI runs without an
 * OpenAI key, so the cited case rewrites the {@code /compare} response in the
 * page into cited answers over a real demo passage and a real vector chunk.
 */
class CompareViewUiTest extends UiTestSupport {

    private static final String READY_LABEL = "Comparison ready — open Compare";

    private Locator askAndWaitForAnswer(String question) {
        int before = page.locator(".message.answer:not(.pending)").count();
        page.locator("#chat-input").fill(question);
        page.locator("#chat-form .send-button").click();
        assertThat(page.locator(".message.answer:not(.pending)"))
                .hasCount(before + 1, new LocatorAssertions.HasCountOptions().setTimeout(20000));
        // nth, not last(): a locator re-resolves, and last() would follow later answers.
        Locator answer = page.locator(".message.answer:not(.pending)").nth(before);
        assertThat(answer.locator(".replay-cta")).isVisible();
        return answer;
    }

    private void compare(Locator answer) {
        answer.locator(".compare-cta").click();
        assertThat(answer.locator(".compare-cta"))
                .hasText(READY_LABEL, new LocatorAssertions.HasTextOptions().setTimeout(20000));
    }

    private Locator replayButton(String label) {
        return page.locator("#compare-replay-row .replay-cta", new Page.LocatorOptions().setHasText(label));
    }

    /** Runs the comparison and returns how many chunks its vector side scored ({@code vector.scoredChunkCount}). */
    private int compareAndReadScoredChunks(Locator answer) {
        com.microsoft.playwright.Response response = page.waitForResponse(
                r -> r.url().endsWith("/compare"), () -> answer.locator(".compare-cta").click());
        assertThat(answer.locator(".compare-cta"))
                .hasText(READY_LABEL, new LocatorAssertions.HasTextOptions().setTimeout(20000));
        Number scored = com.jayway.jsonpath.JsonPath.read(response.text(), "$.vector.scoredChunkCount");
        return scored.intValue();
    }

    @Test
    void anOfflineCompareShowsBothColumnsKeyFiguresAndTheRuleBasedVerdict() {
        List<String> pageErrors = new ArrayList<>();
        page.onPageError(pageErrors::add);
        loadDemoDatasetAndWaitReady();
        Locator answer = askAndWaitForAnswer("Tell me about Irene Adler.");
        int messages = page.locator("#chat-thread .message").count();

        int scored = compareAndReadScoredChunks(answer);

        Locator panel = page.locator("#compare-panel");
        assertThat(panel).isVisible();
        assertThat(page.locator("#tab-compare")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#compare-question")).hasText("Tell me about Irene Adler.");

        Locator columns = page.locator("#compare-grid .compare-col");
        assertThat(columns).hasCount(2);
        assertThat(columns.nth(0).locator(".compare-col-title")).hasText("GraphRAG · Local");
        assertThat(columns.nth(1).locator(".compare-col-title")).hasText("Vector Search");
        assertThat(columns.nth(0).locator(".compare-col-answer")).not().isEmpty();
        assertThat(columns.nth(1).locator(".compare-col-answer"))
                .containsText("Based on the retrieved text passages");

        for (int i = 0; i < 2; i++) {
            Locator figures = columns.nth(i).locator(".compare-figure");
            assertThat(figures).hasCount(4);
            assertThat(figures.nth(0).locator(".compare-figure-label")).containsText("context item");
            assertThat(figures.nth(1).locator(".compare-figure-label")).containsText("document");
            assertThat(figures.nth(3).locator(".compare-figure-value")).containsText("ms");
        }
        // Vector Search always retrieves its top chunks.
        assertThat(columns.nth(1).locator(".compare-figure").nth(0).locator(".compare-figure-value"))
                .not().hasText("0");

        assertThat(page.locator("#compare-verdict-label")).hasText("Rule-based summary");
        assertThat(page.locator("#compare-verdict-text")).containsText("context items");
        assertThat(page.locator("#compare-overlap")).containsText("Vector Search retrieved");

        assertThat(replayButton("Replay GraphRAG")).isVisible();
        assertThat(replayButton("Replay Vector")).isVisible();

        // The similarity ranking: the top chunks by score, the top 5 marked and the footer. The demo
        // index is small, so whether a cut-off line follows depends on its size.
        org.assertj.core.api.Assertions.assertThat(scored).isPositive();
        int shown = Math.min(12, scored);
        int used = Math.min(5, scored);
        Locator ranking = columns.nth(1).locator(".compare-ranking");
        assertThat(ranking.locator(".answer-sources-heading")).hasText("Similarity ranking");
        Locator rankingRows = ranking.locator(".compare-ranking-item");
        assertThat(rankingRows).hasCount(shown);
        assertThat(ranking.locator(".compare-ranking-item--used")).hasCount(used);
        for (int i = 0; i < shown; i++) {
            Locator row = rankingRows.nth(i);
            assertThat(row).hasAttribute("data-rank", String.valueOf(i + 1));
            assertThat(row.locator(".compare-ranking-rank")).hasText(String.valueOf(i + 1));
            assertThat(row.locator(".compare-ranking-score")).hasText(java.util.regex.Pattern.compile("^-?\\d\\.\\d{3}$"));
            assertThat(row.locator(".compare-ranking-doc")).not().isEmpty();
            assertThat(row.locator(".compare-ranking-bar-fill")).hasCount(1);
            if (i < used) {
                assertThat(row).hasClass(java.util.regex.Pattern.compile("compare-ranking-item--used"));
            }
        }
        assertThat(rankingRows.first().locator(".compare-ranking-bar-fill")).hasAttribute("style",
                java.util.regex.Pattern.compile("width: 100(\\.0)?%"));
        assertThat(ranking.locator(".compare-ranking-cutoff")).hasCount(scored > 5 ? 1 : 0);
        assertThat(ranking.locator(".compare-ranking-footer")).hasText(scored + " chunks scored · "
                + (shown == scored ? "showing all " + scored : "showing the top " + shown));
        assertThat(ranking.locator(".compare-ranking-item--used .compare-ranking-used-label"))
                .hasCount(used);
        assertThat(ranking.locator(".compare-ranking-used-label").first()).hasText("used for the answer");
        // A ranking row opens its chunk text.
        rankingRows.first().locator(".compare-ranking-row").click();
        Locator rankingPanel = ranking.locator(".compare-ranking-passage");
        assertThat(rankingPanel).isVisible();
        assertThat(rankingPanel.locator(".answer-passage-text")).not().isEmpty();
        assertThat(rankingPanel.locator(".answer-passage-text")).not().hasText("Passage not available");
        assertThat(rankingRows.first().locator(".compare-ranking-row")).hasAttribute("aria-expanded", "true");

        // Sources of both sides even without citations: the retrieved passages.
        assertThat(columns.nth(0).locator(".compare-retrieved-empty"))
                .hasText("No source passages read (offline keyword matching).");
        Locator vectorRows = columns.nth(1).locator(".compare-retrieved-toggle");
        // One row per retrieved chunk: the vector side's context-item figure.
        String vectorItems = columns.nth(1).locator(".compare-figure").nth(0)
                .locator(".compare-figure-value").textContent();
        assertThat(vectorRows).hasCount(Integer.parseInt(vectorItems.trim()));
        vectorRows.first().click();
        Locator retrievedPanel = columns.nth(1).locator(".compare-retrieved-passage");
        assertThat(retrievedPanel).isVisible();
        assertThat(retrievedPanel.locator(".answer-passage-text")).not().isEmpty();
        assertThat(retrievedPanel.locator(".answer-passage-text")).not().hasText("Passage not available");
        assertThat(vectorRows.first()).hasAttribute("aria-expanded", "true");

        assertThat(page.locator("#compare-fresh-note")).hasText("Both sides were answered fresh for this comparison.");

        // "Run again" re-runs the same question and mode and re-renders.
        com.microsoft.playwright.Response rerun = page.waitForResponse(
                response -> response.url().endsWith("/compare"),
                () -> page.locator("#compare-run-again").click());
        org.assertj.core.api.Assertions.assertThat(rerun.status()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(rerun.request().postData())
                .contains("Tell me about Irene Adler.").contains("LOCAL");
        assertThat(page.locator("#compare-run-again"))
                .hasText("Run again", new LocatorAssertions.HasTextOptions().setTimeout(20000));
        assertThat(page.locator("#compare-run-again")).isEnabled();
        assertThat(page.locator("#compare-question")).hasText("Tell me about Irene Adler.");
        assertThat(page.locator("#tab-compare")).hasAttribute("aria-selected", "true");

        // No extra chat message, no old panel under the answer.
        assertThat(page.locator("#chat-thread .message")).hasCount(messages);
        assertThat(page.locator(".message.answer[data-mode='VECTOR']")).hasCount(0);
        assertThat(answer.locator(".compare-panel")).hasCount(0);
        // The Vector Space tab is gone.
        assertThat(page.locator("#tab-vector-space")).hasCount(0);
        assertThat(page.locator("#vector-space-panel")).hasCount(0);
        assertThat(page.locator(".canvas-tab")).hasCount(2);
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void citedSourcesOpenTheirPassagesAndCarryTheOverlapBadge() {
        loadDemoDatasetAndWaitReady();
        Locator answer = askAndWaitForAnswer("Tell me about Irene Adler.");
        String corpusId = answer.getAttribute("data-corpus-id");

        String textUnitId = (String) page.evaluate(
                "corpusId => fetch('/api/corpora/' + encodeURIComponent(corpusId) + '/graph')"
                        + "  .then(response => response.json())"
                        + "  .then(body => {"
                        + "    for (const entity of body.entities || []) {"
                        + "      for (const source of entity.sources || []) {"
                        + "        if (source.textUnitId) { return source.textUnitId; }"
                        + "      }"
                        + "    }"
                        + "    return null;"
                        + "  })", corpusId);
        org.assertj.core.api.Assertions.assertThat(textUnitId).isNotNull();
        Map<String, Object> passage = (Map<String, Object>) page.evaluate(
                "args => fetch('/api/corpora/' + encodeURIComponent(args.corpusId) + '/text-units/'"
                        + "    + encodeURIComponent(args.id)).then(response => response.json())",
                Map.of("corpusId", corpusId, "id", textUnitId));
        String chunkId = (String) page.evaluate(
                "corpusId => fetch('/api/corpora/' + encodeURIComponent(corpusId) + '/vector-space')"
                        + "  .then(response => response.json()).then(body => body.chunks[0].id)", corpusId);
        Map<String, Object> chunk = (Map<String, Object>) page.evaluate(
                "args => fetch('/api/corpora/' + encodeURIComponent(args.corpusId) + '/chunks/'"
                        + "    + encodeURIComponent(args.id)).then(response => response.json())",
                Map.of("corpusId", corpusId, "id", chunkId));
        org.assertj.core.api.Assertions.assertThat((String) chunk.get("documentName")).isNotBlank();

        page.evaluate("args => {"
                + "  const originalFetch = window.fetch;"
                + "  window.fetch = (input, init) => originalFetch(input, init).then(response => {"
                + "    const url = typeof input === 'string' ? input : input.url;"
                + "    if (!response.ok || !url.endsWith('/compare')) { return response; }"
                + "    return response.json().then(body => {"
                + "      body.graph.answer = 'Adler outwitted Holmes [1].';"
                + "      body.graph.noAnswer = false;"
                + "      body.graph.citations = [{ textUnitId: args.textUnitId, documentName: args.graphDoc,"
                + "          excerpt: 'Graph excerpt.' }];"
                + "      body.vector.answer = 'A photograph is mentioned [1].';"
                + "      body.vector.noAnswer = false;"
                + "      body.vector.citations = [{ chunkId: args.chunkId, documentName: args.chunkDoc,"
                + "          excerpt: 'Chunk excerpt.' }];"
                + "      body.overlap.graph = [{ textUnitId: args.textUnitId, sharedWithVector: true }];"
                + "      body.overlap.vector = [{ chunkId: args.chunkId, sharedWithGraph: true }];"
                + "      body.verdict = { text: 'GraphRAG followed the relationship; vector search matched words.',"
                + "          source: 'llm' };"
                + "      return new Response(JSON.stringify(body),"
                + "          { status: 200, headers: { 'Content-Type': 'application/json' } });"
                + "    });"
                + "  });"
                + "}",
                Map.of("textUnitId", textUnitId, "graphDoc", passage.get("documentName"),
                        "chunkId", chunkId, "chunkDoc", chunk.get("documentName")));

        compare(answer);

        Locator graphColumn = page.locator("#compare-grid .compare-col--graph");
        Locator vectorColumn = page.locator("#compare-grid .compare-col--vector");
        assertThat(graphColumn.locator(".citation-marker")).hasCount(1);
        assertThat(vectorColumn.locator(".citation-marker")).hasCount(1);
        org.assertj.core.api.Assertions.assertThat(vectorColumn.locator(".compare-col-answer").textContent())
                .isEqualTo("A photograph is mentioned [1].");

        assertThat(graphColumn.locator(".answer-sources .answer-source-toggle")).hasText(
                "1. " + passage.get("documentName") + " · Graph excerpt.");
        assertThat(vectorColumn.locator(".answer-sources .answer-source-toggle")).hasText(
                "1. " + chunk.get("documentName") + " · Chunk excerpt.");
        assertThat(graphColumn.locator(".answer-sources .compare-shared-badge"))
                .hasText("also used by the other side");
        assertThat(vectorColumn.locator(".answer-sources .compare-shared-badge"))
                .hasText("also used by the other side");

        // A vector citation opens its chunk's full text; a graph citation its Text Unit.
        String citationPanel = ".answer-passage:not(.compare-retrieved-passage):not(.compare-ranking-passage)";
        vectorColumn.locator(".citation-marker").click();
        assertThat(vectorColumn.locator(citationPanel)).isVisible();
        assertThat(vectorColumn.locator(citationPanel + " .answer-passage-text")).hasText((String) chunk.get("text"));
        graphColumn.locator(".answer-sources .answer-source-toggle").click();
        assertThat(graphColumn.locator(citationPanel + " .answer-passage-text"))
                .hasText((String) passage.get("text"));

        assertThat(page.locator("#compare-verdict-label")).hasText("LLM verdict");
        assertThat(page.locator("#compare-verdict-text"))
                .hasText("GraphRAG followed the relationship; vector search matched words.");
    }

    @Test
    void bothReplayButtonsStartTheirReplays() {
        loadDemoDatasetAndWaitReady();
        int scored = compareAndReadScoredChunks(askAndWaitForAnswer("Tell me about Irene Adler."));

        replayButton("Replay GraphRAG").click();
        assertThat(page.locator("#replay-scrubber")).isVisible();
        assertThat(page.locator("#tab-knowledge-graph")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#compare-panel")).isHidden();
        assertThat(page.locator("#replay-caption")).not().containsText("embedded query");
        page.locator("#replay-close").click();

        page.locator("#tab-compare").click();
        assertThat(page.locator("#compare-panel")).isVisible();
        assertThat(page.locator("#replay-scrubber")).isHidden();
        replayButton("Replay Vector").click();
        // The vector replay stays on the Compare tab, its scrubber visible there.
        assertThat(page.locator("#tab-compare")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#compare-panel")).isVisible();
        assertThat(page.locator("#graph-canvas")).isHidden();
        assertThat(page.locator("#tab-vector-space")).hasCount(0);
        assertThat(page.locator("#replay-scrubber")).isVisible();
        assertThat(page.locator("#replay-caption")).containsText("embedded query");
        assertThat(page.locator("#replay-caption")).containsText(
                "embedded query — scoring " + scored + (scored == 1 ? " chunk" : " chunks"));

        // The VECTOR_QUERY_EMBEDDED step: the ranking is in its replay state, nothing filled or used yet.
        Locator ranking = page.locator("#compare-grid .compare-col--vector .compare-ranking");
        Locator rows = ranking.locator(".compare-ranking-item");
        assertThat(ranking).hasClass(java.util.regex.Pattern.compile("\\bis-replaying\\b"));
        assertThat(ranking.locator(".compare-ranking-item.is-current")).hasCount(0);
        assertThat(ranking.locator(".compare-ranking-item.is-filled")).hasCount(0);
        assertThat(ranking.locator(".compare-ranking-item--used")).hasCount(0);
        int used = ranking.locator(".compare-ranking-item[data-used='true']").count();
        org.assertj.core.api.Assertions.assertThat(used).isBetween(1, 5);
        for (int hit = 1; hit <= used; hit++) {
            page.locator("#replay-step-forward").click();
            Locator current = ranking.locator(".compare-ranking-item.is-current");
            assertThat(current).hasCount(1);
            assertThat(current).hasAttribute("data-rank", String.valueOf(hit));
            assertThat(current.locator(".compare-ranking-row")).hasAttribute("aria-current", "step");
            // Exactly rows 1..hit are filled and marked used.
            assertThat(ranking.locator(".compare-ranking-item.is-filled")).hasCount(hit);
            assertThat(ranking.locator(".compare-ranking-item--used")).hasCount(hit);
            for (int row = 0; row < hit; row++) {
                assertThat(rows.nth(row)).hasClass(java.util.regex.Pattern.compile("\\bis-filled\\b"));
            }
            String score = rows.nth(hit - 1).locator(".compare-ranking-score").textContent();
            assertThat(page.locator("#replay-caption"))
                    .containsText("retrieved chunk: rank " + hit + " · score " + score);
        }
        // Stepping back unfills the last hit.
        page.locator("#replay-step-back").click();
        assertThat(ranking.locator(".compare-ranking-item.is-filled")).hasCount(used - 1);
        page.locator("#replay-step-forward").click();
        assertThat(ranking.locator(".compare-ranking-item.is-filled")).hasCount(used);
        // The synthesis step highlights the vector answer.
        page.locator("#replay-step-forward").click();
        assertThat(page.locator("#compare-grid .compare-col--vector .compare-col-answer.is-replay-current"))
                .hasCount(1);
        assertThat(ranking.locator(".compare-ranking-item.is-current")).hasCount(0);
        assertThat(ranking.locator(".compare-ranking-item.is-filled")).hasCount(used);

        // Switching to the graph tab hides the vector scrubber; back on Compare it returns.
        page.locator("#tab-knowledge-graph").click();
        assertThat(page.locator("#replay-scrubber")).isHidden();
        page.locator("#tab-compare").click();
        assertThat(page.locator("#replay-scrubber")).isVisible();

        page.locator("#replay-close").click();
        assertThat(page.locator("#replay-scrubber")).isHidden();
        // The static view is back: every used row marked, nothing filled or current.
        assertThat(page.locator("#compare-panel .is-current, #compare-panel .is-filled,"
                + " #compare-panel .is-replay-current, #compare-panel .is-replaying,"
                + " #compare-panel [aria-current]")).hasCount(0);
        assertThat(ranking.locator(".compare-ranking-item--used")).hasCount(used);
        assertThat(page.locator("#tab-compare")).hasAttribute("aria-selected", "true");
    }

    @Test
    void aGraphReplaysScrubberBelongsToTheKnowledgeGraphTab() {
        loadDemoDatasetAndWaitReady();
        compare(askAndWaitForAnswer("Tell me about Irene Adler."));

        replayButton("Replay GraphRAG").click();
        assertThat(page.locator("#replay-scrubber")).isVisible();
        page.locator("#tab-compare").click();
        assertThat(page.locator("#replay-scrubber")).isHidden();
        page.locator("#tab-knowledge-graph").click();
        assertThat(page.locator("#replay-scrubber")).isVisible();
    }

    @Test
    void aVectorTraceThatFailsToLoadStaysOnTheCompareTab() {
        loadDemoDatasetAndWaitReady();
        compare(askAndWaitForAnswer("Tell me about Irene Adler."));

        page.route("**/api/traces/**", route -> route.fulfill(new com.microsoft.playwright.Route.FulfillOptions()
                .setStatus(404).setContentType("application/json").setBody("{\"error\":\"not found\"}")));
        replayButton("Replay Vector").click();

        assertThat(page.locator("#tab-compare")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#replay-scrubber")).isVisible();
        assertThat(page.locator("#replay-caption")).containsText("could not be loaded");
    }

    @Test
    void aNormalSizedRankingShowsTwelveRowsTheCutOffAfterRankFiveAndTheFooter() {
        loadDemoDatasetAndWaitReady();
        Locator answer = askAndWaitForAnswer("Tell me about Irene Adler.");

        // The demo index is small; widen the vector side's ranking to a 40-chunk corpus's top 12.
        // A legacy chunk (rank 7) has no document name.
        page.evaluate("() => {"
                + "  const originalFetch = window.fetch;"
                + "  window.fetch = (input, init) => originalFetch(input, init).then(response => {"
                + "    const url = typeof input === 'string' ? input : input.url;"
                + "    if (!response.ok || !url.endsWith('/compare')) { return response; }"
                + "    return response.json().then(body => {"
                + "      const ranking = [];"
                + "      for (let i = 0; i < 12; i++) {"
                + "        ranking.push({ rank: i + 1, chunkId: 'fake-chunk-' + i,"
                + "            documentName: i === 6 ? '' : 'doc-' + i + '.txt', excerpt: '<b>excerpt ' + i + '</b>',"
                + "            score: 0.9 - i * 0.05, used: i < 5 });"
                + "      }"
                + "      body.vector.ranking = ranking;"
                + "      body.vector.scoredChunkCount = 40;"
                + "      return new Response(JSON.stringify(body),"
                + "          { status: 200, headers: { 'Content-Type': 'application/json' } });"
                + "    });"
                + "  });"
                + "}");

        compare(answer);

        Locator ranking = page.locator("#compare-grid .compare-col--vector .compare-ranking");
        Locator rows = ranking.locator(".compare-ranking-item");
        assertThat(rows).hasCount(12);
        assertThat(ranking.locator(".compare-ranking-item--used")).hasCount(5);
        assertThat(rows.nth(0).locator(".compare-ranking-score")).hasText("0.900");
        assertThat(rows.nth(11).locator(".compare-ranking-score")).hasText("0.350");
        assertThat(rows.nth(6).locator(".compare-ranking-doc")).hasText("Unknown document");
        // Text goes in as text, never as markup.
        assertThat(rows.nth(0).locator(".compare-ranking-excerpt")).hasText("<b>excerpt 0</b>");
        assertThat(ranking.locator(".compare-ranking-excerpt b")).hasCount(0);
        // Bar widths are relative to the top score.
        assertThat(rows.nth(0).locator(".compare-ranking-bar-fill")).hasAttribute("style",
                java.util.regex.Pattern.compile("width: 100(\\.0)?%"));
        assertThat(rows.nth(10).locator(".compare-ranking-bar-fill")).hasAttribute("style",
                java.util.regex.Pattern.compile("width: 44\\.4%"));

        Locator cutoff = ranking.locator(".compare-ranking-cutoff");
        assertThat(cutoff).hasCount(1);
        assertThat(cutoff).containsText("top 5 used for the answer");
        Locator items = ranking.locator(".compare-ranking-list > li");
        assertThat(items).hasCount(13);
        assertThat(items.nth(4)).hasAttribute("data-rank", "5");
        assertThat(items.nth(5)).hasClass(java.util.regex.Pattern.compile("compare-ranking-cutoff"));
        assertThat(items.nth(6)).hasAttribute("data-rank", "6");
        assertThat(ranking.locator(".compare-ranking-footer")).hasText("40 chunks scored · showing the top 12");
    }

    @Test
    void aGlobalAnswerIsComparedInGlobalMode() {
        loadDemoDatasetAndWaitReady();
        page.locator("label.mode-choice-option:has(input[value='GLOBAL'])").click();
        Locator answer = askAndWaitForAnswer("What are the major themes across these stories?");
        assertThat(answer).hasAttribute("data-mode", "GLOBAL");

        com.microsoft.playwright.Response response = page.waitForResponse(
                r -> r.url().endsWith("/compare"), () -> answer.locator(".compare-cta").click());
        org.assertj.core.api.Assertions.assertThat(response.request().postData()).contains("GLOBAL");

        assertThat(page.locator("#compare-grid .compare-col--graph .compare-col-title"))
                .hasText("GraphRAG · Global", new LocatorAssertions.HasTextOptions().setTimeout(20000));
        assertThat(page.locator("#tab-compare")).hasAttribute("aria-selected", "true");
    }

    @Test
    @SuppressWarnings("unchecked")
    void replayingTheGraphSideNamesAPassageStep() {
        loadDemoDatasetAndWaitReady();
        Locator answer = askAndWaitForAnswer("Tell me about Irene Adler.");
        String corpusId = answer.getAttribute("data-corpus-id");
        String textUnitId = (String) page.evaluate(
                "corpusId => fetch('/api/corpora/' + encodeURIComponent(corpusId) + '/graph')"
                        + "  .then(response => response.json())"
                        + "  .then(body => {"
                        + "    for (const entity of body.entities || []) {"
                        + "      for (const source of entity.sources || []) {"
                        + "        if (source.textUnitId) { return source.textUnitId; }"
                        + "      }"
                        + "    }"
                        + "    return null;"
                        + "  })", corpusId);
        org.assertj.core.api.Assertions.assertThat(textUnitId).isNotNull();
        Map<String, Object> passage = (Map<String, Object>) page.evaluate(
                "args => fetch('/api/corpora/' + encodeURIComponent(args.corpusId) + '/text-units/'"
                        + "    + encodeURIComponent(args.id)).then(response => response.json())",
                Map.of("corpusId", corpusId, "id", textUnitId));

        // As in CitationsUiTest: the graph trace gets a TEXT_UNIT step for a real demo passage.
        page.evaluate("args => {"
                + "  const originalFetch = window.fetch;"
                + "  window.fetch = (input, init) => originalFetch(input, init).then(response => {"
                + "    const url = typeof input === 'string' ? input : input.url;"
                + "    if (!response.ok || !url.includes('/api/traces/')) { return response; }"
                + "    return response.json().then(body => {"
                + "      const steps = body.steps || [];"
                + "      if (steps.length && steps[0].kind === 'VECTOR_QUERY_EMBEDDED') {"
                + "        return new Response(JSON.stringify(body),"
                + "            { status: 200, headers: { 'Content-Type': 'application/json' } });"
                + "      }"
                + "      body.steps = [{ kind: 'TEXT_UNIT', identifier: args.id, label: 'First excerpt.' }]"
                + "          .concat(steps);"
                + "      return new Response(JSON.stringify(body),"
                + "          { status: 200, headers: { 'Content-Type': 'application/json' } });"
                + "    });"
                + "  });"
                + "}", Map.of("id", textUnitId));

        compare(answer);
        replayButton("Replay GraphRAG").click();

        assertThat(page.locator("#tab-knowledge-graph")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#replay-caption")).containsText(
                "Read passage " + (((Number) passage.get("ordinal")).intValue() + 1) + " of "
                        + passage.get("documentName") + ": First excerpt.");
    }

    @Test
    void theTwoTabsAreKeyboardNavigable() {
        loadDemoDatasetAndWaitReady();
        compare(askAndWaitForAnswer("Tell me about Irene Adler."));

        Locator graphTab = page.locator("#tab-knowledge-graph");
        Locator compareTab = page.locator("#tab-compare");
        assertThat(page.locator("#tab-vector-space")).hasCount(0);

        graphTab.click();
        graphTab.focus();
        page.keyboard().press("ArrowRight");
        assertThat(compareTab).isFocused();
        assertThat(compareTab).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#compare-panel")).isVisible();

        page.keyboard().press("ArrowRight");
        assertThat(graphTab).isFocused();
        assertThat(graphTab).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#compare-panel")).isHidden();

        page.keyboard().press("End");
        assertThat(compareTab).isFocused();
        assertThat(compareTab).hasAttribute("aria-selected", "true");

        page.keyboard().press("Home");
        assertThat(graphTab).isFocused();
        assertThat(graphTab).hasAttribute("aria-selected", "true");

        page.keyboard().press("ArrowLeft");
        assertThat(compareTab).isFocused();
        assertThat(compareTab).hasAttribute("aria-selected", "true");
        page.keyboard().press("ArrowLeft");
        assertThat(graphTab).isFocused();
        assertThat(graphTab).hasAttribute("aria-selected", "true");
        assertThat(compareTab).hasAttribute("aria-selected", "false");
        assertThat(compareTab).hasAttribute("tabindex", "-1");
        assertThat(graphTab).hasAttribute("tabindex", "0");
        assertThat(page.locator("#compare-panel")).isHidden();
    }
}
