package dev.rabauer.graphrag.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Playwright UI tests for the Compare CTA: it runs one comparison, opens the
 * Compare tab (there is no Vector Space tab), replays the vector side on the
 * Compare tab itself, and never adds a chat message of its own.
 *
 * <p>All tests use the offline deterministic stub (no OPENAI_API_KEY),
 * so vector-index chunks are built via the offline {@code LangChain4jEmbeddingPort}
 * during demo-dataset ingestion and are available immediately after "Ready".
 */
class VectorBaselineTriggerUiTest extends UiTestSupport {

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

    @Test
    void compareCtaAppearsOnGraphAnswersAndAddsNoChatMessage() {
        loadDemoDatasetAndWaitReady();
        Locator localAnswer = askAndWaitForAnswer("Tell me about Irene Adler.");
        assertThat(localAnswer).hasAttribute("data-mode", "LOCAL");
        assertThat(localAnswer.locator(".compare-cta")).isVisible();
        int messages = page.locator("#chat-thread .message").count();

        compare(localAnswer);

        assertThat(page.locator("#compare-panel")).isVisible();
        assertThat(page.locator(".message.answer[data-mode='VECTOR']")).hasCount(0);
        assertThat(page.locator("#chat-thread .message")).hasCount(messages);
        assertThat(page.locator(".compare-panel")).hasCount(0);
    }

    @Test
    void compareCtaHasATooltipExplainingWhatItDoesBeforeItIsClicked() {
        loadDemoDatasetAndWaitReady();

        // Story 11-4: the button must carry a title/tooltip AND an aria-label
        // explaining its purpose before the user clicks it — identically for
        // LOCAL and GLOBAL answers.
        java.util.regex.Pattern explanation =
                java.util.regex.Pattern.compile("vector-similarity.*Compare tab", java.util.regex.Pattern.DOTALL);

        Locator localCompareCta = askAndWaitForAnswer("Tell me about Irene Adler.").locator(".compare-cta");
        assertThat(localCompareCta).hasAttribute("title", explanation);
        assertThat(localCompareCta).hasAttribute("aria-label", explanation);

        page.locator("label.mode-choice-option:has(input[value='GLOBAL'])").click();
        Locator globalAnswer = askAndWaitForAnswer("What are the major themes across these stories?");
        assertThat(globalAnswer).hasAttribute("data-mode", "GLOBAL");
        assertThat(globalAnswer.locator(".compare-cta")).hasAttribute("title", explanation);
        assertThat(globalAnswer.locator(".compare-cta")).hasAttribute("aria-label", explanation);
    }

    @Test
    void aComparisonOpensTheCompareTabAndNoVectorSpaceTabExists() {
        loadDemoDatasetAndWaitReady();

        assertThat(page.locator("#tab-vector-space")).hasCount(0);
        assertThat(page.locator("#tab-compare")).isHidden();

        compare(askAndWaitForAnswer("Who is Sherlock Holmes?"));

        assertThat(page.locator("#tab-compare")).isVisible();
        assertThat(page.locator("#tab-compare")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#tab-knowledge-graph")).hasAttribute("aria-selected", "false");
        assertThat(page.locator("#tab-vector-space")).hasCount(0);
        assertThat(page.locator("#vector-space-panel")).hasCount(0);
        assertThat(page.locator("#compare-grid .compare-col--vector .compare-ranking")).isVisible();

        page.locator("#tab-knowledge-graph").click();
        assertThat(page.locator("#tab-knowledge-graph")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#compare-panel")).isHidden();
        assertThat(page.locator("#graph-canvas")).isVisible();
    }

    @Test
    void canvasTabBarIsHiddenBeforeCorpusLoadAndVisibleAfter() {
        page.navigate(baseUrl() + "/");

        assertThat(page.locator("#canvas-tab-bar")).isHidden();

        page.locator("#demo-dataset-button").click();
        assertThat(page.locator("#workflow-status-text"))
                .containsText("Ready", new LocatorAssertions.ContainsTextOptions().setTimeout(20000));
        assertThat(page.locator("#canvas-tab-bar")).isVisible();
    }

    @Test
    void openingReplayOfTheChatAnswerWhileCompareIsActiveSwitchesBackToKnowledgeGraph() {
        loadDemoDatasetAndWaitReady();
        Locator localAnswer = askAndWaitForAnswer("Tell me about Irene Adler.");

        compare(localAnswer);
        assertThat(page.locator("#compare-panel")).isVisible();
        assertThat(page.locator("#replay-scrubber")).isHidden();

        localAnswer.locator(".replay-cta").click();
        assertThat(page.locator("#replay-scrubber")).isVisible();
        assertThat(page.locator("#compare-panel")).isHidden();
        assertThat(page.locator("#tab-knowledge-graph")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#tab-compare")).hasAttribute("aria-selected", "false");
    }

    @Test
    void everyComparisonAndEveryReadyLinkOpensTheCompareTab() {
        loadDemoDatasetAndWaitReady();

        Locator first = askAndWaitForAnswer("Tell me about Irene Adler.");
        compare(first);
        assertThat(page.locator("#tab-compare")).hasAttribute("aria-selected", "true");

        page.locator("#tab-knowledge-graph").click();
        assertThat(page.locator("#tab-knowledge-graph")).hasAttribute("aria-selected", "true");

        Locator second = askAndWaitForAnswer("Who is Sherlock Holmes?");
        compare(second);
        assertThat(page.locator("#tab-compare")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#tab-knowledge-graph")).hasAttribute("aria-selected", "false");
        assertThat(page.locator("#compare-question")).hasText("Who is Sherlock Holmes?");

        // The first answer's ready link reopens the Compare tab on its own comparison.
        page.locator("#tab-knowledge-graph").click();
        first.locator(".compare-cta").click();
        assertThat(page.locator("#tab-compare")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#compare-question")).hasText("Tell me about Irene Adler.");
    }

    @Test
    void aFailedComparisonShowsTheErrorBannerAndReEnablesTheCompareCta() {
        loadDemoDatasetAndWaitReady();
        Locator answer = askAndWaitForAnswer("Tell me about Irene Adler.");

        page.route("**/api/corpora/*/compare", (Route route) -> route.fulfill(
                new Route.FulfillOptions().setStatus(502).setContentType("application/json")
                        .setBody("{\"error\":\"The LLM call failed while writing the answer.\"}")));

        Locator compareCta = answer.locator(".compare-cta");
        compareCta.click();

        assertThat(page.locator("#error-banner")).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(10000));
        assertThat(page.locator("#error-banner")).containsText("The LLM call failed while writing the answer.");
        assertThat(compareCta).isEnabled();
        assertThat(compareCta).hasText("↻ Compare with Vector Search");
        assertThat(page.locator("#tab-compare")).isHidden();
        assertThat(page.locator(".message.answer[data-mode='VECTOR']")).hasCount(0);
    }

    @Test
    void replayingTheVectorSideLabelsStepsAsEmbeddedQueryAndRetrievedChunk() {
        loadDemoDatasetAndWaitReady();
        compare(askAndWaitForAnswer("Tell me about Irene Adler."));

        page.locator("#compare-replay-row .replay-cta", new com.microsoft.playwright.Page.LocatorOptions()
                .setHasText("Replay Vector")).click();

        assertThat(page.locator("#tab-compare")).hasAttribute("aria-selected", "true");
        Locator caption = page.locator("#replay-caption");
        assertThat(caption).isVisible();
        assertThat(caption).not().containsText("matched entity");
        assertThat(caption).containsText("embedded query");

        page.locator("#replay-step-forward").click();
        assertThat(caption).containsText("retrieved chunk: rank 1 · score ");
    }

    @Test
    void theVectorReplayLightsUpTheTopRankingRowOnTheCompareTab() {
        loadDemoDatasetAndWaitReady();
        compare(askAndWaitForAnswer("Tell me about Irene Adler."));

        page.locator("#compare-replay-row .replay-cta", new com.microsoft.playwright.Page.LocatorOptions()
                .setHasText("Replay Vector")).click();
        page.locator("#replay-step-forward").click();

        Locator current = page.locator("#compare-grid .compare-col--vector .compare-ranking-item.is-current");
        assertThat(current).hasCount(1);
        assertThat(current).hasAttribute("data-rank", "1");
        assertThat(current).isVisible();
        assertThat(page.locator("#compare-panel")).isVisible();

        // A graph replay afterwards clears the ranking highlights.
        page.locator("#compare-replay-row .replay-cta", new com.microsoft.playwright.Page.LocatorOptions()
                .setHasText("Replay GraphRAG")).click();
        assertThat(page.locator("#tab-knowledge-graph")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#compare-panel .compare-ranking-item.is-current")).hasCount(0);
    }
}
