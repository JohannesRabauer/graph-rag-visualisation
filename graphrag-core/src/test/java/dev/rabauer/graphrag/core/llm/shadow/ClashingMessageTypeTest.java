package dev.rabauer.graphrag.core.llm.shadow;

import dev.rabauer.graphrag.core.llm.PromptedLlmPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A subclass of {@link PromptedLlmPort} can use a client library's {@code Message}: the port must not
 * inherit a member type of that name that hides it.
 */
class ClashingMessageTypeTest {

    /** Converts each request to the client's own {@link Message} type, as an adapter over a client library does. */
    private static final class ClientPort extends PromptedLlmPort {
        final List<Message> sent = new ArrayList<>();

        @Override
        protected String complete(CompletionRequest request) {
            for (CompletionRequest.Message message : request.messages()) {
                sent.add(new Message(message.role() + ": " + message.text()));
            }
            return "{\"title\":\"T\",\"summary\":\"S\"}";
        }
    }

    @Test
    void theClientsMessageTypeIsUsableInASubclass() {
        ClientPort port = new ClientPort();

        port.summarizeCommunity(List.of(new dev.rabauer.graphrag.core.domain.Entity("A", "Class")), List.of());

        assertEquals(1, port.sent.size());
        Message first = port.sent.getFirst();
        assertEquals(true, first.content().startsWith("USER: "), first.content());
    }
}
