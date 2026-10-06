package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.RetrievalTrace;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.retrieval.LocalRetrievalOptions;
import dev.rabauer.graphrag.core.retrieval.RetrievalResult;
import dev.rabauer.graphrag.core.retrieval.RetrievedItem;
import dev.rabauer.graphrag.core.retrieval.SeedMatch;
import dev.rabauer.graphrag.core.retrieval.SeedMatcher;
import dev.rabauer.graphrag.core.retrieval.SeedMatchers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Retrieval-only Local Search: finds seed Entities with a {@link SeedMatcher},
 * expands from them per {@link LocalRetrievalOptions} and returns the context
 * items and the full trace — no answer is synthesized and no {@code LlmPort}
 * is involved. Meant for consumers that are themselves language models (for
 * example a coding agent) or that synthesize on their own
 * ({@link RetrievalResult#toContextItems()}).
 *
 * <p>Every Entity, Relationship and Text Unit item and step carries the
 * element's locator and attributes.
 */
public class RetrieveLocalContext {

    static final String NO_MATCH_REASON =
            "No Entity in the graph matched the question. Name a class, method or other element of the graph.";

    private final GraphStorePort graph;
    private final SeedMatcher seedMatcher;
    private final LocalRetrievalOptions options;

    /** Keyword seeds, {@link LocalRetrievalOptions#defaults()}. */
    public RetrieveLocalContext(GraphStorePort graph) {
        this(graph, SeedMatchers.defaultFor(null), LocalRetrievalOptions.defaults());
    }

    /** {@link SeedMatchers#defaultFor(EmbeddingPort)} seeds, {@link LocalRetrievalOptions#defaults()}. */
    public RetrieveLocalContext(GraphStorePort graph, EmbeddingPort embeddingPort) {
        this(graph, SeedMatchers.defaultFor(embeddingPort), LocalRetrievalOptions.defaults());
    }

    public RetrieveLocalContext(GraphStorePort graph, SeedMatcher seedMatcher, LocalRetrievalOptions options) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.seedMatcher = seedMatcher == null ? SeedMatchers.defaultFor(null) : seedMatcher;
        this.options = options == null ? LocalRetrievalOptions.defaults() : options;
    }

    /** Retrieves with the configured options. */
    public RetrievalResult retrieve(String question, String corpusId) {
        return retrieve(question, corpusId, options);
    }

    /** Retrieves with {@code options} for this call only. */
    public RetrievalResult retrieve(String question, String corpusId, LocalRetrievalOptions options) {
        LocalRetrievalOptions effective = options == null ? this.options : options;
        List<LocalExpansion.Touch> touches = expand(graph, seedMatcher, question, corpusId, effective);
        if (touches.isEmpty()) {
            return RetrievalResult.empty(RetrievalResult.Mode.LOCAL, question, corpusId,
                    RetrievalResult.Status.NO_MATCH, NO_MATCH_REASON, List.of(), List.of());
        }
        List<RetrievedItem> items = new ArrayList<>();
        List<RetrievalStep> steps = new ArrayList<>();
        for (LocalExpansion.Touch touch : touches) {
            items.add(touch.toRetrievedItem(items.size() + 1));
            steps.add(touch.step());
        }
        return new RetrievalResult(RetrievalResult.Mode.LOCAL, question, corpusId, RetrievalResult.Status.MATCHED,
                "", items, new RetrievalTrace("", steps), List.of());
    }

    /** Seeds for {@code question} and their expansion; empty when no seed matches. */
    static List<LocalExpansion.Touch> expand(GraphStorePort graph, SeedMatcher seedMatcher, String question,
                                             String corpusId, LocalRetrievalOptions options) {
        List<SeedMatch> matches = seedMatcher.match(question, corpusId, graph, options.seedLimit());
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        List<Entity> seeds = new ArrayList<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        for (SeedMatch match : matches.stream().filter(Objects::nonNull).limit(options.seedLimit()).toList()) {
            seeds.add(match.entity());
            scores.putIfAbsent(match.entity().normalizedIdentity(), match.score());
        }
        return new LocalExpansion(graph).expand(seeds, scores, corpusId, options);
    }
}
