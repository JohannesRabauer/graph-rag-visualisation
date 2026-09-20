package com.graphraglens.core.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalTraceTest {

    @Test
    void retrievalStepNormalizesNullIdentifierAndLabelButRequiresANonNullKind() {
        RetrievalStep step = new RetrievalStep(RetrievalStep.Kind.ENTITY, null, null);

        assertEquals("", step.identifier());
        assertEquals("", step.label());

        assertThrows(NullPointerException.class,
                () -> new RetrievalStep(null, "sherlock holmes::person", "Sherlock Holmes"));
    }

    @Test
    void retrievalTraceNormalizesANullTraceIdAndANullStepsListToEmpty() {
        RetrievalTrace trace = new RetrievalTrace(null, null);

        assertEquals("", trace.traceId());
        assertNotNull(trace.steps());
        assertTrue(trace.steps().isEmpty());
    }

    @Test
    void retrievalTraceCopiesItsStepsListRatherThanAliasingTheCallersList() {
        List<RetrievalStep> mutableSteps = new java.util.ArrayList<>();
        mutableSteps.add(new RetrievalStep(RetrievalStep.Kind.COMMUNITY, "community-1", "A community"));

        RetrievalTrace trace = new RetrievalTrace("trace-1", mutableSteps);
        mutableSteps.add(new RetrievalStep(RetrievalStep.Kind.COMMUNITY, "community-2", "Another community"));

        assertEquals(1, trace.steps().size());
        assertThrows(UnsupportedOperationException.class, () -> trace.steps().add(
                new RetrievalStep(RetrievalStep.Kind.COMMUNITY, "community-3", "Yet another community")));
    }
}
