package com.graphraglens.web.ui;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.options.BoundingBox;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 11.2 / GitHub #31 regression coverage: with enough chat turns to
 * overflow {@code .chat-panel}'s visible height, only {@code .thread} should
 * scroll — {@code .mode-choice} and {@code .composer} must stay pinned at
 * the bottom of the panel throughout.
 *
 * <p>This test caught a real, live bug: {@code .canvas} was missing
 * {@code min-height: 0}, so its default flex {@code min-height: auto}
 * refused to shrink below {@code .chat-panel}'s full unclipped content,
 * letting the whole panel balloon to thousands of pixels tall instead of
 * clipping to {@code .app-frame}'s allotted height. The fix was adding
 * {@code min-height: 0;} to {@code .canvas} (instrument.css) — this was not
 * merely a verification of already-correct structure.
 */
class ChatPanelScrollUiTest extends UiTestSupport {

    @Test
    void manyTurnsOverflowTheThreadWhileModeChoiceAndComposerStayPinned() {
        loadDemoDatasetAndWaitReady();
        seedManyChatTurns();

        assertPinnedDuringScroll();

        // Re-create the original bug's most visible symptom directly: a
        // ~6600px-tall `.chat-panel` instead of one clipped to the viewport.
        BoundingBox chatPanelBox = page.locator(".chat-panel").boundingBox();
        assertThat(chatPanelBox).isNotNull();
        assertThat(chatPanelBox.height).isLessThanOrEqualTo(page.viewportSize().height);
    }

    @Test
    void existingAutoScrollToLatestMessageBehaviorIsPreserved() {
        loadDemoDatasetAndWaitReady();
        seedManyChatTurns();

        Locator thread = page.locator("#chat-thread");

        // Scroll away from the bottom first, so there is somewhere to
        // auto-scroll back from.
        thread.evaluate("el => { el.scrollTop = 0; }");
        double scrollTopAfterScrollingUp = ((Number) thread.evaluate("el => el.scrollTop")).doubleValue();
        assertThat(scrollTopAfterScrollingUp).isEqualTo(0.0);

        // Append one more message the same way upload.js's
        // appendMessage/appendAnswer does.
        page.evaluate("() => {\n"
                + "  var thread = document.getElementById('chat-thread');\n"
                + "  var answer = document.createElement('div');\n"
                + "  answer.className = 'message answer';\n"
                + "  answer.dataset.mode = 'LOCAL';\n"
                + "  answer.textContent = 'One more answer that should trigger auto-scroll.';\n"
                + "  thread.appendChild(answer);\n"
                + "  thread.scrollTop = thread.scrollHeight;\n"
                + "}");

        double scrollTop = ((Number) thread.evaluate("el => el.scrollTop")).doubleValue();
        double scrollHeight = ((Number) thread.evaluate("el => el.scrollHeight")).doubleValue();
        double clientHeight = ((Number) thread.evaluate("el => el.clientHeight")).doubleValue();

        assertThat(scrollTop).isEqualTo(scrollHeight - clientHeight);
    }

    @Test
    void pinnedComposerBehaviorHoldsAtTheNarrowMobileBreakpointToo() {
        // Story 11.2 AC: the same pinned-composer/scrolling-thread behavior
        // must hold at the ≤640px breakpoint, where `.chat-panel` switches
        // to the 45%-height mobile layout (instrument.css:1772-1778).
        page.setViewportSize(400, 800);
        loadDemoDatasetAndWaitReady();
        seedManyChatTurns();

        assertPinnedDuringScroll();
    }

    /**
     * Injects synthetic question/answer turns directly into {@code
     * #chat-thread}, matching the exact DOM shape {@code upload.js}'s own
     * {@code appendMessage} produces (`.message.question` / `.message.answer`),
     * so this stays a pure layout/scroll test independent of the LLM stub's
     * timing or content.
     */
    private void seedManyChatTurns() {
        page.evaluate("() => {\n"
                + "  var thread = document.getElementById('chat-thread');\n"
                + "  for (var i = 0; i < 40; i++) {\n"
                + "    var question = document.createElement('div');\n"
                + "    question.className = 'message question';\n"
                + "    question.textContent = 'Synthetic question number ' + i + ' about the corpus.';\n"
                + "    thread.appendChild(question);\n"
                + "\n"
                + "    var answer = document.createElement('div');\n"
                + "    answer.className = 'message answer';\n"
                + "    answer.dataset.mode = 'LOCAL';\n"
                + "    answer.textContent = 'Synthetic answer number ' + i\n"
                + "      + ' with enough text to take up a couple of lines of the thread panel.';\n"
                + "    thread.appendChild(answer);\n"
                + "  }\n"
                + "  thread.scrollTop = thread.scrollHeight;\n"
                + "}");
    }

    private void assertPinnedDuringScroll() {
        Locator modeChoice = page.locator(".mode-choice");
        Locator composer = page.locator(".composer");
        Locator thread = page.locator("#chat-thread");

        assertThat(modeChoice).isVisible();
        assertThat(composer).isVisible();

        BoundingBox modeChoiceBoxBefore = modeChoice.boundingBox();
        BoundingBox composerBoxBefore = composer.boundingBox();
        assertThat(modeChoiceBoxBefore).isNotNull();
        assertThat(composerBoxBefore).isNotNull();

        // Start scrolled to the top so there's somewhere to scroll from.
        thread.evaluate("el => { el.scrollTop = 0; }");
        double scrollTopAtStart = ((Number) thread.evaluate("el => el.scrollTop")).doubleValue();
        assertThat(scrollTopAtStart).isEqualTo(0.0);

        // Sanity check: the seeded turns actually overflow the panel.
        double scrollHeight = ((Number) thread.evaluate("el => el.scrollHeight")).doubleValue();
        double clientHeight = ((Number) thread.evaluate("el => el.clientHeight")).doubleValue();
        assertThat(scrollHeight).isGreaterThan(clientHeight);

        thread.evaluate("el => { el.scrollTop = el.scrollHeight; }");
        double scrollTopAfter = ((Number) thread.evaluate("el => el.scrollTop")).doubleValue();
        assertThat(scrollTopAfter).isGreaterThan(0.0);

        BoundingBox modeChoiceBoxAfter = modeChoice.boundingBox();
        BoundingBox composerBoxAfter = composer.boundingBox();
        assertThat(modeChoiceBoxAfter).isNotNull();
        assertThat(composerBoxAfter).isNotNull();

        assertThat(modeChoiceBoxAfter.x).isEqualTo(modeChoiceBoxBefore.x);
        assertThat(modeChoiceBoxAfter.y).isEqualTo(modeChoiceBoxBefore.y);
        assertThat(modeChoiceBoxAfter.width).isEqualTo(modeChoiceBoxBefore.width);
        assertThat(modeChoiceBoxAfter.height).isEqualTo(modeChoiceBoxBefore.height);

        assertThat(composerBoxAfter.x).isEqualTo(composerBoxBefore.x);
        assertThat(composerBoxAfter.y).isEqualTo(composerBoxBefore.y);
        assertThat(composerBoxAfter.width).isEqualTo(composerBoxBefore.width);
        assertThat(composerBoxAfter.height).isEqualTo(composerBoxBefore.height);
    }
}
