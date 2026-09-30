package com.graphraglens.web.ui;

import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

class RestoredSearchModeUiTest extends UiTestSupport {

    @Test
    void aBrowserRestoredGlobalRadioIsUsedForTheNextQuestionAndShownInTheHint() {
        loadDemoDatasetAndWaitReady();

        page.locator("input[name='search-mode'][value='GLOBAL']")
                .evaluate("input => { input.checked = true; }");
        page.evaluate("() => window.dispatchEvent(new Event('pageshow'))");

        assertThat(page.locator("#mode-hint")).hasText(
                "Global Search aggregates information across Communities to answer broader, corpus-level questions.");

        page.locator("#chat-input").fill("What are the main themes in this corpus?");
        page.locator("#chat-form .send-button").click();

        Locator latestAnswer = page.locator(".message.answer").last();
        assertThat(latestAnswer.locator(".answer-tag")).hasText("Global Search · Answer");
        assertThat(latestAnswer).hasAttribute("data-mode", "GLOBAL");
    }
}
