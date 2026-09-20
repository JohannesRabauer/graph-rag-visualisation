package com.graphraglens.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Real-browser coverage for the Explore page's node-click detail panel
 * (Story 6.2), which previously had no JS-executing test at all (see
 * deferred-work.md's Story 6.2 entry) — a regression in the open/close/swap
 * wiring or the hull-tap-ignored behavior could ship with {@code mvn test}
 * green.
 *
 * <p>Node interactions go through the {@code GraphCanvas.simulateTap} test
 * hook, which fires a real Cytoscape 'tap' event on the target element —
 * exercising the exact same {@code cy.on('tap', ...)} handler wiring a real
 * pointer click would, without depending on pixel coordinates translated
 * through the still-animating force-directed layout (proved flaky under
 * headless browser automation: a node's rendered position keeps moving for
 * ~400ms after the graph loads).
 */
class ExploreDetailPanelUiTest extends UiTestSupport {

    private static final String OPEN_CLASS_PATTERN = ".*\\bis-open\\b.*";

    private void tap(String identity) {
        boolean fired = (boolean) page.evaluate(
                "identity => window.GraphCanvas.simulateTap(identity)", identity);
        org.assertj.core.api.Assertions.assertThat(fired)
                .withFailMessage("Node '%s' was not found on the rendered graph.", identity)
                .isTrue();
    }

    @SuppressWarnings("unchecked")
    private String firstCommunityIdExcluding(String excludedMemberIdentity) {
        return (String) page.evaluate(
                "excluded => fetch('/api/graph').then(r => r.json()).then(body => {"
                        + "  const other = (body.communities || []).find(c => "
                        + "    !(c.memberEntityIdentities || []).includes(excluded));"
                        + "  return other ? other.communityId : null;"
                        + "})",
                excludedMemberIdentity);
    }

    @Test
    void clickingEntitiesOpensClosesAndSwapsTheDetailPanelAndHullTapsAreIgnored() {
        loadDemoDatasetAndWaitReady();

        page.navigate(baseUrl() + "/explore");
        assertThat(page.locator("#graph-canvas"))
                .not().isHidden(new LocatorAssertions.IsHiddenOptions().setTimeout(20000));

        Locator panel = page.locator("#entity-detail-panel");
        Locator name = page.locator("#entity-detail-name");
        Locator type = page.locator("#entity-detail-type");

        // Tap an Entity — the panel opens with that Entity's name/type. This
        // Entity has no Relationships in the demo corpus's deterministic
        // extraction, exercising the "No relationships" fallback line too.
        tap("sherlock holmes::person");
        assertThat(panel).hasClass(java.util.regex.Pattern.compile(OPEN_CLASS_PATTERN));
        assertThat(name).containsText("Sherlock Holmes");
        assertThat(type).containsText("Person");
        assertThat(page.locator("#entity-detail-relationships")).containsText("No relationships");

        // Tap the SAME Entity again — the panel closes.
        tap("sherlock holmes::person");
        assertThat(panel).not().hasClass(java.util.regex.Pattern.compile(OPEN_CLASS_PATTERN));

        // Tap a DIFFERENT Entity — the panel opens directly with the new
        // content (no separate close step needed). "holmes::person" is its
        // own singleton Community in the demo corpus's deterministic
        // extraction (the bare "Holmes" mention, distinct from "Sherlock
        // Holmes").
        tap("sherlock holmes::person");
        assertThat(name).containsText("Sherlock Holmes");
        tap("holmes::person");
        assertThat(panel).hasClass(java.util.regex.Pattern.compile(OPEN_CLASS_PATTERN));
        assertThat(name).containsText("Holmes");

        // Close it, then tap a Community hull — hull taps are routed to
        // focusCommunity, never to the Entity detail callback, so the panel
        // must stay closed.
        tap("holmes::person");
        assertThat(panel).not().hasClass(java.util.regex.Pattern.compile(OPEN_CLASS_PATTERN));

        String communityId = firstCommunityIdExcluding("sherlock holmes::person");
        org.assertj.core.api.Assertions.assertThat(communityId).isNotNull();
        tap("community::" + communityId);
        assertThat(panel).not().hasClass(java.util.regex.Pattern.compile(OPEN_CLASS_PATTERN));
    }
}
