package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.ComparisonFacts;
import dev.rabauer.graphrag.core.domain.ComparisonStats;
import dev.rabauer.graphrag.core.domain.ComparisonVerdict;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.port.LlmPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ComparisonVerdictTest {

    @Test
    void theRuleNamesTheSideWithMoreDocumentsAndItemsAndTheOverlap() {
        ComparisonFacts facts = new ComparisonFacts("drift", new ComparisonStats(9, 3, 120),
                new ComparisonStats(5, 1, 40), 5, 2);

        ComparisonVerdict verdict = ComparisonVerdict.ruleBased(facts);

        assertEquals(ComparisonVerdict.Source.RULE, verdict.source());
        assertEquals("GraphRAG (DRIFT) retrieved passages from more distinct documents (3 vs 1). "
                + "GraphRAG (DRIFT) retrieved more context items (9 vs 5). "
                + "2 of the 5 passages Vector Search retrieved were also read by GraphRAG.", verdict.text());
    }

    @Test
    void theRuleHandlesTiesAVectorLeadAndNoPassages() {
        assertEquals("Both sides retrieved passages from the same number of distinct documents (1). "
                        + "Vector Search retrieved more context items (5 vs 2). "
                        + "1 of the 5 passages Vector Search retrieved was also read by GraphRAG.",
                ComparisonVerdict.ruleBased(new ComparisonFacts("LOCAL", new ComparisonStats(2, 1, 0),
                        new ComparisonStats(5, 1, 0), 5, 1)).text());
        assertEquals("Both sides retrieved passages from the same number of distinct documents (0). "
                        + "Both sides retrieved the same number of context items (0). "
                        + "Vector Search retrieved no passages to compare.",
                ComparisonVerdict.ruleBased(new ComparisonFacts(null, null, null, 0, 0)).text());
    }

    @Test
    void theRuleNeverClaimsCorrectness() {
        String text = ComparisonVerdict.ruleBased(new ComparisonFacts("GLOBAL", new ComparisonStats(4, 2, 0),
                new ComparisonStats(5, 1, 0), 5, 0)).text().toLowerCase(Locale.ROOT);
        assertFalse(text.contains("correct"));
        assertFalse(text.contains("better"));
    }

    @Test
    void theLlmPortDefaultIsTheRule() {
        LlmPort port = corpus -> new GraphExtraction(List.of(), List.of());
        ComparisonFacts facts = new ComparisonFacts("LOCAL", new ComparisonStats(1, 1, 0),
                new ComparisonStats(1, 1, 0), 1, 1);

        assertEquals(ComparisonVerdict.ruleBased(facts), port.compareAnswers("q", "a", "b", facts));
    }
}
