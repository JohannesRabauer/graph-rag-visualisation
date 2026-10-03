package dev.rabauer.graphrag.web.ui;

import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * The four-way comparison on the Compare tab: one column per retrieval
 * method with its outcome, time split, traversal footprint and replay; the
 * evidence table; the per-column diagnosis once a passage is marked as the
 * expected evidence; the JSON log download; and switching back to the two-way
 * comparison. CI runs offline, so the graph modes read no passages and only
 * Vector Search fills the evidence table.
 */
class CompareAllViewUiTest extends UiTestSupport {

    private static final String READY_LABEL = "Four-way comparison ready — open Compare";

    private Locator askAndWaitForAnswer(String question) {
        int before = page.locator(".message.answer:not(.pending)").count();
        page.locator("#chat-input").fill(question);
        page.locator("#chat-form .send-button").click();
        assertThat(page.locator(".message.answer:not(.pending)"))
                .hasCount(before + 1, new LocatorAssertions.HasCountOptions().setTimeout(20000));
        Locator answer = page.locator(".message.answer:not(.pending)").nth(before);
        assertThat(answer.locator(".replay-cta")).isVisible();
        return answer;
    }

    private Locator column(String method) {
        return page.locator("#compare-all-grid .compare-all-col[data-method='" + method + "']");
    }

    @Test
    void theFourWayComparisonShowsEveryMethodTheEvidenceAndADiagnosisPerColumn() throws Exception {
        List<String> pageErrors = new ArrayList<>();
        page.onPageError(pageErrors::add);
        loadDemoDatasetAndWaitReady();
        Locator answer = askAndWaitForAnswer("Tell me about Irene Adler.");
        int messages = page.locator("#chat-thread .message").count();

        com.microsoft.playwright.Response response = page.waitForResponse(
                r -> r.url().endsWith("/compare-all"), () -> answer.locator(".compare-all-cta").click());
        org.assertj.core.api.Assertions.assertThat(response.status()).isEqualTo(200);
        // The demo index is small: Vector Search keeps its top 5, or every chunk when there are fewer.
        int scored = ((Number) com.jayway.jsonpath.JsonPath.read(response.text(), "$.runs[3].scoredChunkCount"))
                .intValue();
        org.assertj.core.api.Assertions.assertThat(scored).isPositive();
        int used = Math.min(5, scored);
        assertThat(answer.locator(".compare-all-cta"))
                .hasText(READY_LABEL, new LocatorAssertions.HasTextOptions().setTimeout(20000));

        assertThat(page.locator("#tab-compare")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#compare-all")).isVisible();
        assertThat(page.locator("#compare-pair")).isHidden();
        assertThat(page.locator("#compare-all-question")).hasText("Tell me about Irene Adler.");
        assertThat(page.locator("#compare-all-summary")).containsText("methods answered");

        Locator columns = page.locator("#compare-all-grid .compare-all-col");
        assertThat(columns).hasCount(4);
        String[] methods = {"LOCAL", "GLOBAL", "DRIFT", "VECTOR"};
        String[] labels = {"Local", "Global", "DRIFT", "Vector Search"};
        for (int i = 0; i < 4; i++) {
            Locator col = columns.nth(i);
            assertThat(col).hasAttribute("data-method", methods[i]);
            assertThat(col.locator(".compare-col-title")).hasText(labels[i]);
            assertThat(col.locator(".compare-all-outcome")).not().isEmpty();
            assertThat(col.locator(".compare-col-answer")).not().isEmpty();
            assertThat(col.locator(".compare-all-total")).hasText(Pattern.compile("^[\\d,]+ ms$"));
            assertThat(col.locator(".compare-all-bar-seg")).hasCount(3);
            assertThat(col.locator(".compare-all-time-detail")).containsText("retrieval");
            assertThat(col.locator(".compare-all-time-detail")).containsText("LLM");
            assertThat(col.locator(".compare-all-footprint li").first()).isVisible();
            assertThat(col.locator(".compare-all-diagnosis")).isHidden();
            assertThat(col.locator(".replay-cta")).hasText(Pattern.compile("^Replay " + labels[i] + " — \\d+ steps?$"));
        }
        assertThat(column("VECTOR").locator(".compare-all-footprint li[data-kind='VECTOR_CHUNK']"))
                .hasText(used + (used == 1 ? " chunk retrieved" : " chunks retrieved"));
        assertThat(column("VECTOR").locator(".compare-all-time-detail")).containsText("embedding");

        // Offline, only Vector Search reaches passages: its top 5 in context, the rest of its top 12 below the cut-off.
        Locator rows = page.locator("#compare-evidence tbody tr");
        int rowCount = Math.min(12, scored);
        assertThat(rows).hasCount(rowCount);
        assertThat(page.locator("#compare-evidence thead th")).hasCount(6);
        assertThat(page.locator("#compare-evidence td[data-method='VECTOR'] .compare-evidence-mark[data-use='IN_CONTEXT']"))
                .hasCount(used);
        assertThat(page.locator("#compare-evidence td[data-method='LOCAL'] .compare-evidence-mark[data-use='NOT_RETRIEVED']"))
                .hasCount(rowCount);
        assertThat(rows.first().locator("td[data-method='VECTOR'] .compare-evidence-mark")).hasText("in context · #1");
        assertThat(page.locator("#compare-evidence-clear")).isDisabled();

        // A passage opens its full text.
        rows.first().locator(".compare-evidence-toggle").click();
        Locator passage = page.locator("#compare-evidence .compare-evidence-passage");
        assertThat(passage).isVisible();
        assertThat(passage.locator(".answer-passage-text")).not().isEmpty();
        assertThat(passage.locator(".answer-passage-text")).not().hasText("Passage not available");

        // Marking the top chunk as expected: Vector Search had it in context but cited nothing (offline).
        rows.first().locator("input[type='radio']").check();
        assertThat(rows.first()).hasClass(Pattern.compile("is-expected"));
        assertThat(column("VECTOR").locator(".compare-all-diagnosis")).isVisible();
        assertThat(column("VECTOR").locator(".compare-all-diagnosis")).hasAttribute("data-stage", "generation");
        assertThat(column("VECTOR").locator(".compare-all-diagnosis-stage")).hasText("Generation");
        assertThat(column("LOCAL").locator(".compare-all-diagnosis")).hasAttribute("data-stage", "retrieval");
        assertThat(column("LOCAL").locator(".compare-all-diagnosis-text")).containsText("read no source passages");
        assertThat(page.locator("#compare-evidence-clear")).isEnabled();

        // A chunk below the cut-off is a ranking miss for Vector Search.
        Locator belowCutoff = page.locator("#compare-evidence tbody tr",
                new Page.LocatorOptions().setHas(page.locator(".compare-evidence-mark[data-use='RANKED_BELOW_CUTOFF']")));
        if (belowCutoff.count() > 0) {
            belowCutoff.first().locator("input[type='radio']").check();
            assertThat(column("VECTOR").locator(".compare-all-diagnosis")).hasAttribute("data-stage", "ranking");
            assertThat(column("VECTOR").locator(".compare-all-diagnosis-text"))
                    .containsText("below the top-5 cut-off");
        }

        // The log holds every run with its steps, the evidence and the expected passage's diagnosis.
        Download download = page.waitForDownload(() -> page.locator("#compare-all-download").click());
        org.assertj.core.api.Assertions.assertThat(download.suggestedFilename())
                .startsWith("graphrag-compare-").endsWith(".json");
        String log = Files.readString(download.path());
        org.assertj.core.api.Assertions.assertThat((List<String>) com.jayway.jsonpath.JsonPath.read(log, "$.runs[*].method"))
                .containsExactly(methods);
        org.assertj.core.api.Assertions.assertThat((String) com.jayway.jsonpath.JsonPath.read(log, "$.question"))
                .isEqualTo("Tell me about Irene Adler.");
        org.assertj.core.api.Assertions.assertThat((Object) com.jayway.jsonpath.JsonPath.read(log, "$.expectedEvidence.id"))
                .isNotNull();
        org.assertj.core.api.Assertions.assertThat((List<Object>) com.jayway.jsonpath.JsonPath.read(log, "$.runs[3].steps"))
                .isNotEmpty();
        org.assertj.core.api.Assertions.assertThat((String) com.jayway.jsonpath.JsonPath.read(log, "$.runs[0].diagnosis.stage"))
                .isEqualTo("retrieval");

        // Clearing hides every diagnosis again.
        page.locator("#compare-evidence-clear").click();
        assertThat(page.locator("#compare-all-grid .compare-all-diagnosis:visible")).hasCount(0);
        assertThat(page.locator("#compare-evidence tbody tr.is-expected")).hasCount(0);

        // "Run again" repeats the question.
        com.microsoft.playwright.Response rerun = page.waitForResponse(
                r -> r.url().endsWith("/compare-all"), () -> page.locator("#compare-all-run-again").click());
        org.assertj.core.api.Assertions.assertThat(rerun.status()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(rerun.request().postData()).contains("Tell me about Irene Adler.");
        assertThat(page.locator("#compare-all-run-again"))
                .hasText("Run again", new LocatorAssertions.HasTextOptions().setTimeout(20000));
        assertThat(columns).hasCount(4);

        // The two-way comparison replaces the four-way view, and the ready link brings it back.
        answer.locator(".compare-cta").click();
        assertThat(answer.locator(".compare-cta")).hasText("Comparison ready — open Compare",
                new LocatorAssertions.HasTextOptions().setTimeout(20000));
        assertThat(page.locator("#compare-pair")).isVisible();
        assertThat(page.locator("#compare-all")).isHidden();
        assertThat(page.locator("#compare-grid .compare-col")).hasCount(2);
        assertThat(page.locator("#compare-all-grid .compare-all-col")).hasCount(0);
        answer.locator(".compare-all-cta").click();
        assertThat(page.locator("#compare-all")).isVisible();
        assertThat(page.locator("#compare-grid .compare-col")).hasCount(0);
        assertThat(columns).hasCount(4);

        // Nothing was added to the chat.
        assertThat(page.locator("#chat-thread .message")).hasCount(messages);
        org.assertj.core.api.Assertions.assertThat(pageErrors).isEmpty();
    }
}
