package com.graphraglens.web.ui;

import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

class DriftModeChoiceUiTest extends UiTestSupport {

    @Test
    void selectingDriftUpdatesTheHintAndAppliesTheDriftColorTokens() {
        loadDemoDatasetAndWaitReady();

        Locator driftOption = page.locator("label.mode-choice-option:has(input[value='DRIFT'])");
        driftOption.click();

        assertThat(page.locator("#mode-hint"))
                .hasText("DRIFT runs a community pass, spawns targeted sub-questions, then re-ranks and synthesizes.");

        String labelColor = String.valueOf(driftOption.evaluate(
                "option => getComputedStyle(option).color"));
        String dotBorderColor = String.valueOf(driftOption.locator(".mode-choice-dot").evaluate(
                "dot => getComputedStyle(dot).borderColor"));
        String dotFillColor = String.valueOf(driftOption.locator(".mode-choice-dot").evaluate(
                "dot => getComputedStyle(dot, '::after').backgroundColor"));

        org.assertj.core.api.Assertions.assertThat(labelColor).isEqualTo("rgb(192, 34, 95)");
        org.assertj.core.api.Assertions.assertThat(dotBorderColor).isEqualTo("rgb(192, 34, 95)");
        org.assertj.core.api.Assertions.assertThat(dotFillColor).isEqualTo("rgb(192, 34, 95)");
    }

    @Test
    void driftQueriesStayClearlyLabeledWhenTheBackendReturnsTheNotImplementedReason() {
        loadDemoDatasetAndWaitReady();

        page.locator("label.mode-choice-option:has(input[value='DRIFT'])").click();
        page.locator("#chat-input").fill("What happens in drift mode?");
        page.locator("#chat-form .send-button").click();

        Locator latestAnswer = page.locator(".message.answer").last();
        assertThat(latestAnswer.locator(".answer-tag")).hasText("Drift Search · Answer");
        assertThat(latestAnswer).containsText("DRIFT Search isn't implemented yet.");
        assertThat(latestAnswer).hasAttribute("data-mode", "DRIFT");

        String tagColor = String.valueOf(latestAnswer.locator(".answer-tag").evaluate(
                "tag => getComputedStyle(tag).color"));
        org.assertj.core.api.Assertions.assertThat(tagColor).isEqualTo("rgb(192, 34, 95)");
    }
}
