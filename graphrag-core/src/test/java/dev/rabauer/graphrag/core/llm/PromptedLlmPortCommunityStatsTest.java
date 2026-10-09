package dev.rabauer.graphrag.core.llm;

import dev.rabauer.graphrag.core.domain.CommunityStats;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Community summary prompt names the size and the module counts of the whole Community. */
class PromptedLlmPortCommunityStatsTest {

    private static final String REPLY = "{\"title\":\"Brokers\",\"summary\":\"About brokers.\"}";

    private static List<Entity> listed(int count) {
        List<Entity> members = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            members.add(new Entity("C" + i, "Class", "", List.of(), Map.of("module", "broker"), null));
        }
        return members;
    }

    private static final List<Relationship> NONE = List.of();

    @Test
    void thePromptNamesTheFullSizeAndTheCountsByModule() {
        PromptedLlmPortTest.ScriptedPort port = new PromptedLlmPortTest.ScriptedPort(REPLY);
        List<Entity> all = new ArrayList<>(listed(130));
        all.subList(0, 40).replaceAll(entity -> entity.withAttributes(Map.of("module", "client")));
        CommunityStats stats = CommunityStats.of(all, 25, List.of("module", "package"));

        port.summarizeCommunity(listed(25), NONE, stats);

        String prompt = port.requests.getFirst().lastUserText();
        assertTrue(prompt.contains("The group has 130 members in total; only 25 are listed below"), prompt);
        assertTrue(prompt.contains("Members by module: broker (90), client (40)."), prompt);
        assertFalse(prompt.contains("Members by package"), prompt);
        assertTrue(prompt.indexOf("Members by module") < prompt.indexOf("Members:\n"), prompt);
    }

    @Test
    void aLongValueListIsCut() {
        PromptedLlmPortTest.ScriptedPort port = new PromptedLlmPortTest.ScriptedPort(REPLY);
        List<Entity> all = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            all.add(new Entity("C" + i, "Class", "", List.of(), Map.of("module", "m" + i), null));
        }

        port.summarizeCommunity(all, NONE, CommunityStats.of(all, 9, List.of("module")));

        String prompt = port.requests.getFirst().lastUserText();
        assertTrue(prompt.contains("m1 (1), m2 (1), m3 (1), m4 (1), m5 (1), m6 (1), and 3 more."), prompt);
    }

    @Test
    void aSmallCommunityWithoutAttributesKeepsTheOldPromptExactly() {
        PromptedLlmPortTest.ScriptedPort plain = new PromptedLlmPortTest.ScriptedPort(REPLY, REPLY);
        List<Entity> members = List.of(new Entity("A", "Class"), new Entity("B", "Class"));

        plain.summarizeCommunity(members, NONE);
        plain.summarizeCommunity(members, NONE, CommunityStats.of(members));

        assertEquals(plain.requests.get(0).lastUserText(), plain.requests.get(1).lastUserText());
        assertFalse(plain.requests.get(1).lastUserText().contains("members in total"));
    }

    @Test
    void theTwoArgumentMethodStillWorksOnItsOwn() {
        PromptedLlmPortTest.ScriptedPort port = new PromptedLlmPortTest.ScriptedPort(REPLY);

        assertEquals("About brokers.", port.summarizeCommunity(listed(3), NONE).summary());
    }
}
