package com.graphraglens.web.ui;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

class EntityDetailSourcesUiTest extends UiTestSupport {

    // The shared Neo4j container keeps other tests' corpora; without this the
    // page-load auto-restore of a leftover corpus races the demo click.
    private void disableAutoRestore() {
        page.route("**/api/corpora", (Route route) -> {
            if ("GET".equals(route.request().method())) {
                route.fulfill(new Route.FulfillOptions()
                        .setStatus(200)
                        .setContentType("application/json")
                        .setBody("{\"corpora\":[]}"));
            } else {
                route.resume();
            }
        });
    }

    @Test
    void entityDetailShowsDescriptionLazySourcePassagesFailuresAndRelationshipTooltips() {
        disableAutoRestore();
        String body = "event: entity-extracted\n"
                + "data: {\"type\":\"entity-extracted\",\"data\":"
                + "{\"identity\":\"ada lovelace::person\",\"name\":\"Ada Lovelace\",\"type\":\"Person\","
                + "\"description\":\"Ada wrote notes about computing.\","
                + "\"sources\":["
                + "{\"textUnitId\":\"tu-ok\",\"documentName\":\"engine.txt\",\"ordinal\":2},"
                + "{\"textUnitId\":\"tu-missing\",\"documentName\":\"engine.txt\",\"ordinal\":3}]}}\n\n"
                + "event: entity-extracted\n"
                + "data: {\"type\":\"entity-extracted\",\"data\":"
                + "{\"identity\":\"analytical engine::product\",\"name\":\"Analytical Engine\",\"type\":\"Product\","
                + "\"description\":\"\",\"sources\":[]}}\n\n"
                + "event: relationship-extracted\n"
                + "data: {\"type\":\"relationship-extracted\",\"data\":"
                + "{\"sourceIdentity\":\"ada lovelace::person\",\"source\":\"Ada Lovelace\","
                + "\"targetIdentity\":\"analytical engine::product\",\"target\":\"Analytical Engine\","
                + "\"type\":\"wrote_about\",\"description\":\"Ada described the Engine.\"}}\n\n";
        page.route("**/api/corpora/*/progress", (Route route) -> route.fulfill(
                new Route.FulfillOptions()
                        .setStatus(200)
                        .setContentType("text/event-stream")
                        .setBody(body)));
        java.util.List<String> passageRequestUrls = new java.util.concurrent.CopyOnWriteArrayList<>();
        page.route("**/api/corpora/*/text-units/**", (Route route) -> {
            passageRequestUrls.add(route.request().url());
            if (route.request().url().contains("tu-missing")) {
                route.fulfill(new Route.FulfillOptions()
                        .setStatus(404)
                        .setContentType("application/json")
                        .setBody("{\"error\":\"No text passage was found.\"}"));
            } else {
                route.fulfill(new Route.FulfillOptions()
                        .setStatus(200)
                        .setContentType("application/json")
                        .setBody("{\"id\":\"tu-ok\",\"documentName\":\"engine.txt\",\"ordinal\":2,"
                                + "\"text\":\"The Analytical Engine passage text.\"}"));
            }
        });

        page.navigate(baseUrl() + "/");
        com.microsoft.playwright.Response demoResponse = page.waitForResponse(
                response -> response.url().endsWith("/api/corpora/demo"),
                () -> page.locator("#demo-dataset-button").click());
        String corpusId = demoResponse.text().replaceAll(".*\"corpusId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        page.waitForFunction("() => window.GraphCanvas && window.GraphCanvas.simulateTap('ada lovelace::person')",
                null, new Page.WaitForFunctionOptions().setTimeout(20000));

        assertThat(page.locator("#entity-detail-description-section")).isVisible();
        assertThat(page.locator("#entity-detail-description")).hasText("Ada wrote notes about computing.");
        assertThat(page.locator("#entity-detail-sources-section")).isVisible();
        assertThat(page.locator("#entity-detail-sources")).containsText("engine.txt · passage 3");
        assertThat(page.locator("#entity-detail-relationships li")).hasAttribute("title", "Ada described the Engine.");

        page.locator("#entity-detail-sources button").filter(
                new com.microsoft.playwright.Locator.FilterOptions().setHasText("passage 3")).click();
        assertThat(page.locator("#entity-detail-sources")).containsText("The Analytical Engine passage text.");

        page.locator("#entity-detail-sources button").filter(
                new com.microsoft.playwright.Locator.FilterOptions().setHasText("passage 4")).click();
        assertThat(page.locator("#entity-detail-sources")).containsText("Passage not available");
        org.assertj.core.api.Assertions.assertThat(passageRequestUrls).hasSize(2).allSatisfy(url ->
                org.assertj.core.api.Assertions.assertThat(url)
                        .contains("/api/corpora/" + java.net.URLEncoder.encode(corpusId,
                                java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20") + "/text-units/"));
    }

    private static final String FAKE_EVENT_SOURCE = "(() => {"
            + "  window.__fakeSources = [];"
            + "  function FakeEventSource(url) { this.url = url; this.listeners = {}; this.onerror = null;"
            + "    window.__fakeSources.push(this); }"
            + "  FakeEventSource.prototype.addEventListener = function (type, fn) {"
            + "    (this.listeners[type] = this.listeners[type] || []).push(fn); };"
            + "  FakeEventSource.prototype.close = function () { this.closed = true; };"
            + "  window.__emitSse = function (type, data) {"
            + "    var source = window.__fakeSources[window.__fakeSources.length - 1];"
            + "    (source.listeners[type] || []).forEach(function (fn) {"
            + "      fn({ data: JSON.stringify({ type: type, data: data }) }); }); };"
            + "  window.EventSource = FakeEventSource;"
            + "})();";

    @Test
    void openPanelFollowsReEmittedEntitiesAndHidesEmptySectionsAndTooltips() {
        disableAutoRestore();
        page.addInitScript(FAKE_EVENT_SOURCE);
        page.navigate(baseUrl() + "/");
        page.locator("#demo-dataset-button").click();
        page.waitForFunction("() => window.__fakeSources && window.__fakeSources.length > 0",
                null, new Page.WaitForFunctionOptions().setTimeout(20000));

        page.evaluate("() => {"
                + "window.__emitSse('entity-extracted', {identity: 'ada lovelace::person', name: 'Ada Lovelace',"
                + "  type: 'Person', description: 'First.', sources: [{textUnitId: 'tu-0', documentName: 'engine.txt', ordinal: 0}]});"
                + "window.__emitSse('entity-extracted', {identity: 'analytical engine::product', name: 'Analytical Engine',"
                + "  type: 'Product', description: '', sources: []});"
                + "window.__emitSse('relationship-extracted', {sourceIdentity: 'ada lovelace::person', source: 'Ada Lovelace',"
                + "  targetIdentity: 'analytical engine::product', target: 'Analytical Engine', type: 'wrote_about', description: ''});"
                + "}");
        page.waitForFunction("() => window.GraphCanvas.simulateTap('ada lovelace::person')",
                null, new Page.WaitForFunctionOptions().setTimeout(20000));

        assertThat(page.locator("#entity-detail-description")).hasText("First.");
        assertThat(page.locator("#entity-detail-sources button")).hasCount(1);
        assertThat(page.locator("#entity-detail-sources button")).hasText("engine.txt · passage 1");
        assertThat(page.locator("#entity-detail-relationships li")).hasCount(1);
        assertThat(page.locator("#entity-detail-relationships li")).not().hasAttribute("title",
                java.util.regex.Pattern.compile(".*"));

        page.evaluate("() => {"
                + "window.__emitSse('entity-extracted', {identity: 'ada lovelace::person', name: 'Ada Lovelace',"
                + "  type: 'Person', description: 'First. Second.', sources: ["
                + "  {textUnitId: 'tu-0', documentName: 'engine.txt', ordinal: 0},"
                + "  {textUnitId: 'tu-4', documentName: 'engine.txt', ordinal: 4}]});"
                + "window.__emitSse('relationship-extracted', {sourceIdentity: 'ada lovelace::person', source: 'Ada Lovelace',"
                + "  targetIdentity: 'analytical engine::product', target: 'Analytical Engine', type: 'wrote_about',"
                + "  description: 'Now described.'});"
                + "}");

        assertThat(page.locator("#entity-detail-description")).hasText("First. Second.");
        assertThat(page.locator("#entity-detail-sources button")).hasCount(2);
        assertThat(page.locator("#entity-detail-sources button").nth(1)).hasText("engine.txt · passage 5");
        assertThat(page.locator("#entity-detail-relationships li")).hasCount(1);
        assertThat(page.locator("#entity-detail-relationships li")).hasAttribute("title", "Now described.");

        page.waitForFunction("() => window.GraphCanvas.simulateTap('analytical engine::product')",
                null, new Page.WaitForFunctionOptions().setTimeout(20000));
        assertThat(page.locator("#entity-detail-name")).hasText("Analytical Engine");
        assertThat(page.locator("#entity-detail-description-section")).isHidden();
        assertThat(page.locator("#entity-detail-sources-section")).isHidden();
    }
}
