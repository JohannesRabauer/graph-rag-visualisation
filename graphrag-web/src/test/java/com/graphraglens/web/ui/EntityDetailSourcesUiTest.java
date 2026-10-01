package com.graphraglens.web.ui;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

class EntityDetailSourcesUiTest extends UiTestSupport {

    @Test
    void entityDetailShowsDescriptionLazySourcePassagesFailuresAndRelationshipTooltips() {
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
        page.route("**/api/corpora/*/text-units/**", (Route route) -> {
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
        page.locator("#demo-dataset-button").click();
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
    }
}
