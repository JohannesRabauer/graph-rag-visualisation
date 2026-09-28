package com.graphraglens.web.ui;

import com.microsoft.playwright.ConsoleMessage;
import com.microsoft.playwright.Route;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for spec-11-3-fix-retrieval-trace-replay-not-rendering.md
 * (GitHub #32): {@code replay.js}'s init guard (its early-return when one of
 * the four transport elements it requires is missing from the DOM) logs a
 * {@code console.error} naming the missing element(s) before bailing out.
 * This drives that guard for each of the four required ids in turn by
 * removing the element from the served HTML (via route interception, so it
 * is genuinely absent before {@code replay.js}'s IIFE runs, not merely
 * hidden), then asserts the diagnostic is emitted and names the removed id.
 */
class ReplayMissingScrubberElementUiTest extends UiTestSupport {

    // Thymeleaf strips its own `th:src` attribute when it renders the
    // template server-side, leaving just `src="/js/replay.js"` in the HTML
    // actually served — match on that substring (not the raw template
    // source) so the insertion point below is found in the rendered page.
    private static final String REPLAY_SCRIPT_SRC = "src=\"/js/replay.js\"";

    @ParameterizedTest
    @ValueSource(strings = {
            "replay-scrubber",
            "replay-step-back",
            "replay-play-pause",
            "replay-step-forward"
    })
    void logsAConsoleErrorNamingTheMissingElement(String missingElementId) {
        // Strip the guarded element out of the served HTML before replay.js's
        // own <script> tag runs, so its init guard genuinely finds it absent
        // from the DOM (not just hidden) when the IIFE executes.
        page.route(baseUrl() + "/", route -> {
            String body = route.fetch().text();
            int scriptTagStart = body.lastIndexOf("<script", body.indexOf(REPLAY_SCRIPT_SRC));
            assertThat(scriptTagStart).isGreaterThanOrEqualTo(0);
            String removalScript = "<script>var el = document.getElementById('" + missingElementId
                    + "'); if (el) { el.remove(); }</script>\n  ";
            String withElementRemoved = body.substring(0, scriptTagStart)
                    + removalScript
                    + body.substring(scriptTagStart);
            route.fulfill(new Route.FulfillOptions()
                    .setBody(withElementRemoved)
                    .setContentType("text/html"));
        });

        ConsoleMessage consoleError = page.waitForConsoleMessage(
                new com.microsoft.playwright.Page.WaitForConsoleMessageOptions()
                        .setPredicate(msg -> "error".equals(msg.type())
                                && msg.text().contains("[replay] missing required DOM element(s):")),
                () -> page.navigate(baseUrl() + "/"));

        assertThat(consoleError.text()).contains("#" + missingElementId);
    }
}
