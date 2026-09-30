package com.graphraglens.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.FilePayload;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Playwright UI tests for
 * spec-12-7-build-the-corpus-history-switcher-and-auto-restore-on-load.md:
 * {@code GET /api/corpora}/{@code POST .../activate} (Story 12.6) had no
 * frontend caller until this story wired {@code activateCorpus}, the
 * corpus-history switcher popover, and a page-load bootstrap on top of them.
 *
 * <p>The shared Neo4j test container ({@link com.graphraglens.web.SharedNeo4jTestContainer})
 * is never reset between test classes/methods, so other tests' corpora may
 * already be sitting in the registry when these run. Assertions are written
 * to tolerate that: the two corpora this test ingests are always the most
 * recently created (and, until anything else activates something newer,
 * the most-recently-activated too), so they always land at index 0/1 of
 * the ordered list -- but the list's total size is never asserted.
 */
class CorpusSwitcherUiTest extends UiTestSupport {

    private Locator historyToggle() {
        return page.locator("#corpus-history-toggle");
    }

    private Locator historyPopover() {
        return page.locator("#corpus-history-popover");
    }

    private Locator historyRows() {
        return page.locator(".corpus-history-row");
    }

    private void uploadTextCorpusAndWaitReady(String filename, String content) {
        page.setInputFiles("#corpus-file-input",
                new FilePayload(filename, "text/plain", content.getBytes(StandardCharsets.UTF_8)));
        assertThat(page.locator("#workflow-status-text"))
                .containsText("Ready", new LocatorAssertions.ContainsTextOptions().setTimeout(20000));
    }

    /** Records every {@code POST .../activate} request fired from here on. */
    private List<String> trackActivateRequests() {
        List<String> activateRequests = new CopyOnWriteArrayList<>();
        page.onRequest(request -> {
            if ("POST".equals(request.method()) && request.url().contains("/activate")) {
                activateRequests.add(request.url());
            }
        });
        return activateRequests;
    }

    @Test
    void switcherListsBothCorporaMostRecentFirstAndSwitchesInPlaceWithoutReload() {
        loadDemoDatasetAndWaitReady();
        uploadTextCorpusAndWaitReady("second-corpus.txt",
                "A wholly different corpus about lighthouses and tide charts, unrelated to Sherlock Holmes.");

        List<String> activateRequests = trackActivateRequests();

        // A marker only a full page reload would clear -- proves the
        // upcoming switch re-renders in place rather than reloading.
        page.evaluate("() => { window.__noReloadMarker = 'still-here'; }");

        assertThat(historyToggle())
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        historyToggle().click();
        assertThat(historyPopover()).isVisible();

        // Most-recently-activated (the just-uploaded second corpus) first.
        assertThat(historyRows().nth(0).locator(".corpus-history-row-name"))
                .containsText("second-corpus.txt", new LocatorAssertions.ContainsTextOptions().setTimeout(10000));
        assertThat(historyRows().nth(1).locator(".corpus-history-row-name"))
                .containsText("Sherlock Holmes");

        // Switch back to the earlier (non-active) corpus.
        historyRows().nth(1).click();

        assertThat(page.locator("#corpus-chip")).containsText("Sherlock Holmes",
                new LocatorAssertions.ContainsTextOptions().setTimeout(20000));
        assertThat(page.locator("#workflow-status-text"))
                .containsText("Ready", new LocatorAssertions.ContainsTextOptions().setTimeout(20000));
        assertThat(page.locator("#graph-canvas")).isVisible();
        assertThat(page.locator("#canvas-idle")).isHidden();

        Object marker = page.evaluate("() => window.__noReloadMarker");
        org.assertj.core.api.Assertions.assertThat(marker).isEqualTo("still-here");

        // The manual switch fired POST .../activate for the newly-selected corpus.
        org.assertj.core.api.Assertions.assertThat(activateRequests).isNotEmpty();
    }

    @Test
    void reloadingAutoRestoresTheMostRecentlyActivatedCorpus() {
        loadDemoDatasetAndWaitReady();
        uploadTextCorpusAndWaitReady("second-corpus.txt",
                "A wholly different corpus about lighthouses and tide charts, unrelated to Sherlock Holmes.");

        List<String> activateRequests = trackActivateRequests();

        page.navigate(baseUrl() + "/");

        // Auto-restored without any click: the most-recently-activated
        // corpus (the second one) shows automatically.
        assertThat(page.locator("#corpus-chip")).containsText("second-corpus.txt",
                new LocatorAssertions.ContainsTextOptions().setTimeout(20000));
        assertThat(page.locator("#workflow-status-text"))
                .containsText("Ready", new LocatorAssertions.ContainsTextOptions().setTimeout(20000));
        assertThat(page.locator("#canvas-idle")).isHidden();

        assertThat(historyToggle())
                .isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        historyToggle().click();
        assertThat(page.locator(".corpus-history-row.is-active")).hasCount(1);
        assertThat(page.locator(".corpus-history-row.is-active .corpus-history-row-name"))
                .containsText("second-corpus.txt");

        // Auto-restore-on-load fired POST .../activate too.
        org.assertj.core.api.Assertions.assertThat(activateRequests).isNotEmpty();
    }
}
