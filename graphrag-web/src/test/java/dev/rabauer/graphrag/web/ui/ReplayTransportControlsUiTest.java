package dev.rabauer.graphrag.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.assertions.LocatorAssertions;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Regression coverage for {@code replay.js}'s transport-button boundary and
 * autoplay logic and the {@code .replay-cta}'s {@code data-trace-id}/label
 * wiring — the gap left open by spec-5-2-replay-the-retrieval-trace.md's
 * deferred-work.md entry: the earlier {@code ReplayCommunityHullVisibilityUiTest}
 * covers {@code highlightStep()}'s rendered effect, but nothing exercised
 * step-forward/back disablement at either end, autoplay stopping itself at
 * the last step, or the zero-step case.
 */
class ReplayTransportControlsUiTest extends UiTestSupport {

    @Test
    void stepBackIsDisabledAtTheFirstStepAndStepForwardAtTheLast() {
        loadDemoDatasetAndWaitReady();

        // LOCAL is the default mode. The demo corpus's deterministic
        // extraction ties "Professor Moriarty" to "Holmes" via a
        // (fallback "related_to") Relationship in its third document, so
        // this question's tokens seed on "Professor Moriarty" (the better
        // keyword match) and its one-hop walk lands a real multi-step
        // (Entity/Relationship/Entity) trace — unlike "Sherlock Holmes",
        // whose own full-name Entity (from the first document) has no
        // Relationship of its own touching it.
        page.locator("#chat-input").fill("Tell me about Professor Moriarty and Holmes.");
        page.locator("#chat-form .send-button").click();

        Locator replayCta = page.locator(".replay-cta").last();
        assertThat(replayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        replayCta.click();

        // openReplay()'s trace fetch is async — the scrubber only becomes
        // visible (and the counter/ticks populated) once it resolves, so
        // wait for that before reading any of its rendered state.
        assertThat(page.locator("#replay-scrubber")).not().isHidden();

        Locator stepBack = page.locator("#replay-step-back");
        Locator stepForward = page.locator("#replay-step-forward");
        Locator playPause = page.locator("#replay-play-pause");
        Locator counter = page.locator("#replay-step-counter");

        String[] initialCount = counter.textContent().split(" / ");
        int total = Integer.parseInt(initialCount[1].trim());
        org.assertj.core.api.Assertions.assertThat(total).isGreaterThan(1);

        // At step 1: back is disabled, forward is enabled.
        assertThat(stepBack).isDisabled();
        assertThat(stepForward).isEnabled();

        // Walk to the last step.
        for (int i = 1; i < total; i++) {
            stepForward.click();
        }
        assertThat(stepForward).isDisabled();
        assertThat(playPause).isDisabled();
        assertThat(stepBack).isEnabled();
        org.assertj.core.api.Assertions.assertThat(counter.textContent()).startsWith(pad(total));

        // Stepping back re-enables forward and disables autoplay's own
        // boundary state changes accordingly.
        stepBack.click();
        assertThat(stepForward).isEnabled();
        assertThat(playPause).isEnabled();
    }

    @Test
    void autoplayStopsItselfAtTheLastStep() {
        loadDemoDatasetAndWaitReady();

        page.locator("#chat-input").fill("Tell me about Professor Moriarty and Holmes.");
        page.locator("#chat-form .send-button").click();

        Locator replayCta = page.locator(".replay-cta").last();
        assertThat(replayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        replayCta.click();

        assertThat(page.locator("#replay-scrubber")).not().isHidden();

        Locator playPause = page.locator("#replay-play-pause");
        Locator stepForward = page.locator("#replay-step-forward");
        Locator counter = page.locator("#replay-step-counter");

        String[] initialCount = counter.textContent().split(" / ");
        int total = Integer.parseInt(initialCount[1].trim());

        playPause.click();
        // hasClass matches the full class attribute unless given a regex —
        // the button carries other classes too, so match with a pattern.
        assertThat(playPause).hasClass(java.util.regex.Pattern.compile(".*\\bis-playing\\b.*"));

        // Autoplay advances one step at a time; wait until it has stopped
        // itself (class removed) rather than asserting a fixed step count,
        // since the interval timing is real (PLAY_INTERVAL_MS in replay.js).
        // replay.js's own interval only calls stopPlayback() on the tick
        // *after* reaching the last step (it checks-then-acts), so
        // stepForward.disabled and the is-playing class clear up to one
        // full PLAY_INTERVAL_MS apart — wait for the class separately
        // rather than asserting it right after stepForward disables.
        assertThat(stepForward).isDisabled(new LocatorAssertions.IsDisabledOptions().setTimeout(20000));
        assertThat(playPause).not().hasClass(
                java.util.regex.Pattern.compile(".*\\bis-playing\\b.*"),
                new LocatorAssertions.HasClassOptions().setTimeout(5000));
        org.assertj.core.api.Assertions.assertThat(counter.textContent()).startsWith(pad(total));
    }

    @Test
    void zeroStepTraceDisablesEveryTransportControl() {
        loadDemoDatasetAndWaitReady();

        // A question with no graph-grounded match yields an empty trace
        // (LocalSearchAnswer.noMatch()) — still a successful answer, per its
        // own contract, with a Replay CTA advertising "0 steps".
        page.locator("#chat-input").fill("zzzznonexistentqueryterm12345");
        page.locator("#chat-form .send-button").click();

        Locator replayCta = page.locator(".replay-cta").last();
        assertThat(replayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));
        assertThat(replayCta).containsText("0 steps");
        replayCta.click();

        assertThat(page.locator("#replay-scrubber")).not().isHidden();

        Locator stepBack = page.locator("#replay-step-back");
        Locator stepForward = page.locator("#replay-step-forward");
        Locator playPause = page.locator("#replay-play-pause");
        Locator counter = page.locator("#replay-step-counter");
        Locator caption = page.locator("#replay-caption");

        assertThat(stepBack).isDisabled();
        assertThat(stepForward).isDisabled();
        assertThat(playPause).isDisabled();
        assertThat(counter).hasText("00 / 00");
        assertThat(caption).containsText("Nothing was touched for this answer.");
    }

    @Test
    void replayCtaCarriesATraceIdAndAStepCountLabel() {
        loadDemoDatasetAndWaitReady();

        page.locator("#chat-input").fill("Tell me about Professor Moriarty and Holmes.");
        page.locator("#chat-form .send-button").click();

        Locator replayCta = page.locator(".replay-cta").last();
        assertThat(replayCta).isVisible(new LocatorAssertions.IsVisibleOptions().setTimeout(20000));

        String traceId = replayCta.getAttribute("data-trace-id");
        org.assertj.core.api.Assertions.assertThat(traceId).isNotBlank();
        assertThat(replayCta).containsText("Replay this answer's Retrieval Trace");
        assertThat(replayCta).containsText("steps");
    }

    private static String pad(int value) {
        String text = String.valueOf(value);
        return text.length() < 2 ? "0" + text : text;
    }
}
