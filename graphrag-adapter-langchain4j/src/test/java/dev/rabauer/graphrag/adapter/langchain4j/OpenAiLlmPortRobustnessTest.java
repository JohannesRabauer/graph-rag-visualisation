package dev.rabauer.graphrag.adapter.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.rabauer.graphrag.core.domain.CommunitySummary;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.llm.PromptedLlmPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiLlmPortRobustnessTest {

    private static final List<Entity> MEMBERS = List.of(new Entity("Sherlock Holmes", "Person"),
            new Entity("Dr. Watson", "Person"));
    private static final List<Relationship> RELATIONSHIPS = List.of(
            new Relationship("Sherlock Holmes", "Person", "knows", "Dr. Watson", "Person"));

    /** Replies in order and records each request's messages. */
    private static final class ScriptedChatModel implements ChatModel {
        private final Deque<String> replies;
        private final List<List<ChatMessage>> requests = new ArrayList<>();

        private ScriptedChatModel(String... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request.messages());
            return ChatResponse.builder().aiMessage(AiMessage.from(replies.removeFirst()))
                    .finishReason(FinishReason.STOP).build();
        }
    }

    private static OpenAiLlmPort oneShot(ChatModel json) {
        return new OpenAiLlmPort(json, PromptedLlmPort.Options.defaults().withCorrectiveRetry(false));
    }

    private static OpenAiLlmPort withRetry(ChatModel json) {
        return new OpenAiLlmPort(json, PromptedLlmPort.Options.defaults());
    }

    @Test
    void parsesAJsonObjectWrappedInProse() {
        ScriptedChatModel json = new ScriptedChatModel(
                "Sure, here it is: {\"title\": \"Baker Street\", \"summary\": \"Holmes and Watson.\",} Hope it helps!");

        CommunitySummary summary = oneShot(json).summarizeCommunity(MEMBERS, RELATIONSHIPS);

        assertEquals("Baker Street", summary.title());
        assertEquals("Holmes and Watson.", summary.summary());
    }

    @Test
    void theCorrectiveRetryAsksOnceMoreWithTheBadReply() {
        ScriptedChatModel json = new ScriptedChatModel("They are friends.",
                "{\"title\": \"Friends\", \"summary\": \"Holmes knows Watson.\"}");

        CommunitySummary summary = withRetry(json).summarizeCommunity(MEMBERS, RELATIONSHIPS);

        assertEquals("Holmes knows Watson.", summary.summary());
        assertEquals(2, json.requests.size());
        List<ChatMessage> retry = json.requests.get(1);
        assertEquals(3, retry.size());
        assertEquals("They are friends.", ((AiMessage) retry.get(1)).text());
        assertTrue(((dev.langchain4j.data.message.UserMessage) retry.get(2)).singleText().contains("only one JSON object"));
    }

    @Test
    void withoutTheCorrectiveRetryAnUnusableReplyFailsAtOnce() {
        ScriptedChatModel json = new ScriptedChatModel("They are friends.", "{\"title\":\"x\",\"summary\":\"y\"}");

        assertThrows(OpenAiLlmPort.LlmCallFailedException.class,
                () -> oneShot(json).summarizeCommunity(MEMBERS, RELATIONSHIPS));
        assertEquals(1, json.requests.size());
    }

    @Test
    void theCorrectiveRetryFailsVisiblyWhenTheSecondReplyIsUnusableToo() {
        ScriptedChatModel json = new ScriptedChatModel("No.", "Still no.");

        OpenAiLlmPort.LlmCallFailedException failure = assertThrows(OpenAiLlmPort.LlmCallFailedException.class,
                () -> withRetry(json).summarizeCommunity(MEMBERS, RELATIONSHIPS));

        assertTrue(failure.getMessage().contains("Still no."));
        assertEquals(2, json.requests.size());
    }
}
