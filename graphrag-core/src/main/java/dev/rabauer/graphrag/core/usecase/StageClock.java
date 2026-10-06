package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Chunk;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunitySummary;
import dev.rabauer.graphrag.core.domain.ComparisonFacts;
import dev.rabauer.graphrag.core.domain.ComparisonVerdict;
import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.StageTiming;
import dev.rabauer.graphrag.core.domain.SynthesizedAnswer;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.LlmPort;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Times one retrieval run by stage. {@link #llm(LlmPort)} and
 * {@link #embedding(EmbeddingPort)} wrap the run's ports so every call is
 * timed and counted; {@link #timing()} then reports the run's total, the
 * time inside those calls, and the rest as retrieval.
 *
 * <p>Not thread-safe: one clock per sequential run.
 */
final class StageClock {

    private final long startNanos = System.nanoTime();
    private long llmNanos;
    private int llmCalls;
    private long embeddingNanos;
    private int embeddingCalls;

    /** {@code delegate} with every model call timed; null stays null. */
    LlmPort llm(LlmPort delegate) {
        return delegate == null ? null : new TimedLlmPort(delegate);
    }

    /** {@code delegate} with every embedding call timed; null stays null. */
    EmbeddingPort embedding(EmbeddingPort delegate) {
        return delegate == null ? null : new TimedEmbeddingPort(delegate);
    }

    /** The stage split from this clock's creation until now. */
    StageTiming timing() {
        long total = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        long llm = TimeUnit.NANOSECONDS.toMillis(llmNanos);
        long embedding = TimeUnit.NANOSECONDS.toMillis(embeddingNanos);
        return new StageTiming(total, total - llm - embedding, embedding, embeddingCalls, llm, llmCalls);
    }

    private <T> T timeLlm(Supplier<T> call) {
        long start = System.nanoTime();
        try {
            return call.get();
        } finally {
            llmNanos += System.nanoTime() - start;
            llmCalls++;
        }
    }

    /**
     * Forwards every {@link LlmPort} method, so the delegate's own overrides
     * answer; only the model calls are timed ({@link #synthesizesAnswers()}
     * is a flag, not a call). {@code StageClockTest} guards that every
     * interface method is overridden here.
     */
    private final class TimedLlmPort implements LlmPort {
        private final LlmPort delegate;

        private TimedLlmPort(LlmPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public GraphExtraction extract(Corpus corpus) {
            return timeLlm(() -> delegate.extract(corpus));
        }

        @Override
        public GraphExtraction extract(TextUnit unit, List<String> entityTypes) {
            return timeLlm(() -> delegate.extract(unit, entityTypes));
        }

        @Override
        public String summarizeCommunity(Collection<Entity> members) {
            return timeLlm(() -> delegate.summarizeCommunity(members));
        }

        @Override
        public CommunitySummary summarizeCommunity(Collection<Entity> members,
                                                   Collection<Relationship> relationships) {
            return timeLlm(() -> delegate.summarizeCommunity(members, relationships));
        }

        @Override
        public List<String> deriveDriftSubQuestions(String question, Collection<Community> communities) {
            return timeLlm(() -> delegate.deriveDriftSubQuestions(question, communities));
        }

        @Override
        public String synthesizeFromChunks(String question, List<Chunk> chunks) {
            return timeLlm(() -> delegate.synthesizeFromChunks(question, chunks));
        }

        @Override
        public boolean synthesizesAnswers() {
            return delegate.synthesizesAnswers();
        }

        @Override
        public boolean extractsEntities() {
            return delegate.extractsEntities();
        }

        @Override
        public boolean summarizesCommunities() {
            return delegate.summarizesCommunities();
        }

        @Override
        public boolean derivesSubQuestions() {
            return delegate.derivesSubQuestions();
        }

        @Override
        public SynthesizedAnswer synthesizeAnswer(String question, List<ContextItem> context) {
            return timeLlm(() -> delegate.synthesizeAnswer(question, context));
        }

        @Override
        public ComparisonVerdict compareAnswers(String question, String graphAnswer, String vectorAnswer,
                                                ComparisonFacts facts) {
            return timeLlm(() -> delegate.compareAnswers(question, graphAnswer, vectorAnswer, facts));
        }

        @Override
        public GraphExtraction extractEntitiesAndRelationships(Corpus corpus) {
            return timeLlm(() -> delegate.extractEntitiesAndRelationships(corpus));
        }
    }

    private final class TimedEmbeddingPort implements EmbeddingPort {
        private final EmbeddingPort delegate;

        private TimedEmbeddingPort(EmbeddingPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public float[] embed(String text) {
            long start = System.nanoTime();
            try {
                return delegate.embed(text);
            } finally {
                embeddingNanos += System.nanoTime() - start;
                embeddingCalls++;
            }
        }

        @Override
        public boolean isSemantic() {
            return delegate.isSemantic();
        }
    }
}
