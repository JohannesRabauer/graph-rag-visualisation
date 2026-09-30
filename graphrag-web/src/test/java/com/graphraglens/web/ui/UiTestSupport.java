package com.graphraglens.web.ui;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.graphraglens.web.SharedNeo4jTestContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.neo4j.driver.Session;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared real-browser UI test harness (AD-15 amendment — see graphrag-web's
 * pom.xml comment and ARCHITECTURE-SPINE.md): starts the actual Spring Boot
 * app on a random port and drives it with a real, already-installed headless
 * Chromium via Playwright, so the vanilla JS this module ships (Replay
 * scrubber, node-tap/detail-panel, community-hull toggling) gets genuine
 * browser-behavior coverage that {@code mvn test} previously had no way to
 * catch (see deferred-work.md's Story 5.2/6.1/6.2 entries).
 *
 * <p>Always uses the LOCAL, deterministic, offline LLM stub — no
 * {@code OPENAI_API_KEY} is read or required (ParserConfig's own fallback) —
 * so these tests stay fast, free, and reproducible.
 *
 * <p>The app itself loads Cytoscape.js from a CDN (index.html),
 * which this sandbox's egress policy blocks outright — not a proxy
 * configuration issue, a hard 403 organization-policy denial. Rather than
 * depend on live network access at all (flaky, environment-specific), every
 * page intercepts that one request and fulfills it from a vendored copy at
 * {@code src/test/resources/vendor/cytoscape.min.js} (fetched once from the
 * npm registry, same library/version the app already pins) — tests stay
 * fully offline and deterministic regardless of what a given environment's
 * egress policy allows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class UiTestSupport {

    @DynamicPropertySource
    static void neo4jProperties(DynamicPropertyRegistry registry) {
        SharedNeo4jTestContainer.registerDynamicProperties(registry);
    }

    @LocalServerPort
    private int port;

    private static Playwright playwright;
    private static Browser browser;
    private static Path vendoredCytoscapePath;

    protected Page page;

    @BeforeAll
    static void launchBrowser() throws URISyntaxException {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));

        URL resource = UiTestSupport.class.getClassLoader().getResource("vendor/cytoscape.min.js");
        if (resource == null) {
            throw new IllegalStateException(
                    "Missing test resource vendor/cytoscape.min.js — required to run UI tests offline.");
        }
        vendoredCytoscapePath = Paths.get(resource.toURI());
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void newPage() {
        // Story 12.7 (auto-restore on load) means a fresh `page.navigate("/")`
        // is no longer neutral: if any earlier test method (in this class or
        // an earlier one -- the Neo4j container backing every UiTestSupport
        // class in this module is a JVM-wide singleton, see
        // SharedNeo4jTestContainer) left a corpus registered, the very next
        // navigation would silently auto-restore it and hide #canvas-idle's
        // upload controls before this test ever gets to use them. Wiping the
        // whole graph before each test method restores every existing test's
        // original assumption -- a fresh "/" load always starts idle.
        try (Session session = SharedNeo4jTestContainer.driver().session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (n) DETACH DELETE n");
                return null;
            });
        }

        page = browser.newPage();
        page.route("**/cytoscape@3.28.1/dist/cytoscape.min.js", (Route route) -> route.fulfill(
                new Route.FulfillOptions()
                        .setPath(vendoredCytoscapePath)
                        .setContentType("application/javascript")));
    }

    @AfterEach
    void closePage() {
        if (page != null) {
            page.close();
        }
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * Navigates to the main screen, loads the built-in Sherlock Holmes Demo
     * Dataset, and waits for the workflow status to read Ready — i.e.
     * ingestion AND community detection have both finished, since Global
     * Search's own readiness depends on Communities already existing.
     */
    protected void loadDemoDatasetAndWaitReady() {
        page.navigate(baseUrl() + "/");
        page.locator("#demo-dataset-button").click();
        assertThat(page.locator("#workflow-status-text"))
                .containsText("Ready", new LocatorAssertions.ContainsTextOptions().setTimeout(20000));
    }

    /**
     * Steps forward through the currently-open trace (opened via the most
     * recently rendered {@code .replay-cta}) until some step's identifier is
     * genuinely marked {@code step-active} on the canvas (same technique as
     * {@code ReplayRelationshipEdgeHighlightUiTest}/{@code DriftTreeReplayUiTest}),
     * proving Replay is not just visible but actually driving the graph
     * canvas.
     */
    @SuppressWarnings("unchecked")
    protected void assertReplayHighlightsAStepOnTheCanvas() {
        List<Map<String, Object>> traceSteps = (List<Map<String, Object>>) page.evaluate(
                "() => {"
                        + "  const ctas = document.querySelectorAll('.replay-cta');"
                        + "  const traceId = ctas[ctas.length - 1].dataset.traceId;"
                        + "  return fetch('/api/traces/' + traceId)"
                        + "    .then(response => response.json())"
                        + "    .then(body => body.steps || []);"
                        + "}");
        assertThat(traceSteps).isNotEmpty();

        Locator stepForward = page.locator("#replay-step-forward");
        boolean highlighted = false;
        for (int i = 0; i < traceSteps.size() && !highlighted; i++) {
            String identifier = (String) traceSteps.get(i).get("identifier");
            if (identifier != null) {
                Boolean isActive = (Boolean) page.evaluate(
                        "id => window.GraphCanvas.elementHasClass(id, 'step-active')", identifier);
                if (Boolean.TRUE.equals(isActive)) {
                    highlighted = true;
                    break;
                }
            }
            if (Boolean.TRUE.equals(stepForward.isDisabled())) {
                break;
            }
            stepForward.click();
        }

        assertThat(highlighted).isTrue();
    }
}
