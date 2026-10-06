package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.RetrievalTrace;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphReadPort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.retrieval.DriftRetrievalOptions;
import dev.rabauer.graphrag.core.retrieval.RetrievalResult;
import dev.rabauer.graphrag.core.retrieval.RetrievedItem;
import dev.rabauer.graphrag.core.retrieval.SeedMatcher;
import dev.rabauer.graphrag.core.retrieval.SeedMatchers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Retrieval-only DRIFT Search: picks candidate Communities like
 * {@link RetrieveGlobalContext}, asks the {@link LlmPort} for one sub-question
 * per candidate ({@link LlmPort#deriveDriftSubQuestions}; the deterministic
 * default without a port), runs a Local expansion per sub-question, and
 * returns the candidate Communities plus the de-duplicated union of the
 * branch items, with the full trace. No answer is synthesized.
 *
 * <p>Trace: one {@code COMMUNITY} step per candidate, then per sub-question a
 * {@code SUB_QUESTION_SPAWNED} step (identifier: its Community's id, label:
 * the sub-question) followed by that branch's steps. A branch whose
 * sub-question matches no seed falls back to the original question's seeds.
 * A failing sub-question derivation either propagates or, with
 * {@link dev.rabauer.graphrag.core.usecase.FailurePolicy#ISOLATE_ITEM}, falls
 * back to the deterministic sub-questions with a warning in the result.
 */
public class RetrieveDriftContext {

    private final GraphReadPort graph;
    private final LlmPort llmPort;
    private final EmbeddingPort embeddingPort;
    private final SeedMatcher seedMatcher;
    private final DriftRetrievalOptions options;

    public RetrieveDriftContext(GraphReadPort graph) {
        this(graph, null, null, null, DriftRetrievalOptions.defaults());
    }

    /**
     * @param llmPort       derives the sub-questions; null uses the deterministic ones
     * @param embeddingPort semantic: Communities and seeds by meaning
     * @param seedMatcher   seeds each branch and scores Community members;
     *                      null means {@link SeedMatchers#defaultFor(EmbeddingPort)}
     */
    public RetrieveDriftContext(GraphReadPort graph, LlmPort llmPort, EmbeddingPort embeddingPort,
                                SeedMatcher seedMatcher, DriftRetrievalOptions options) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.llmPort = llmPort == null ? LlmPort.none() : llmPort;
        this.embeddingPort = embeddingPort;
        this.seedMatcher = seedMatcher == null ? SeedMatchers.defaultFor(embeddingPort) : seedMatcher;
        this.options = options == null ? DriftRetrievalOptions.defaults() : options;
    }

    public RetrievalResult retrieve(String question, String corpusId) {
        return retrieve(question, corpusId, options);
    }

    public RetrievalResult retrieve(String question, String corpusId, DriftRetrievalOptions options) {
        DriftRetrievalOptions effective = options == null ? this.options : options;
        RetrieveGlobalContext.CommunityContext context = RetrieveGlobalContext.CommunityContext.load(graph, corpusId);
        if (context.communities().isEmpty()) {
            return RetrievalResult.empty(RetrievalResult.Mode.DRIFT, question, corpusId,
                    RetrievalResult.Status.NO_COMMUNITIES, RetrieveGlobalContext.NO_COMMUNITIES_REASON, List.of(),
                    List.of());
        }
        List<RetrieveGlobalContext.Scored> candidates = RetrieveGlobalContext.candidates(graph, embeddingPort,
                seedMatcher, question, corpusId, effective.communities(), context, new HashMap<>());
        if (candidates.isEmpty()) {
            return RetrievalResult.empty(RetrievalResult.Mode.DRIFT, question, corpusId,
                    RetrievalResult.Status.NO_MATCH, RetrieveGlobalContext.NO_MATCH_REASON, List.of(), List.of());
        }

        List<String> warnings = new ArrayList<>();
        List<Community> communities = candidates.stream().map(RetrieveGlobalContext.Scored::community).toList();
        List<String> subQuestions = subQuestions(question, communities, effective, warnings);

        List<RetrievalStep> steps = new ArrayList<>();
        Map<String, LocalExpansion.Touch> union = new LinkedHashMap<>();
        for (RetrieveGlobalContext.Scored candidate : candidates) {
            LocalExpansion.Touch touch = RetrieveGlobalContext.communityTouch(candidate.community(), candidate.score());
            steps.add(touch.step());
            union.putIfAbsent(touch.item().key(), touch);
        }
        for (int i = 0; i < subQuestions.size(); i++) {
            String subQuestion = subQuestions.get(i);
            String parentId = i < communities.size() ? communities.get(i).id() : "";
            steps.add(new RetrievalStep(RetrievalStep.Kind.SUB_QUESTION_SPAWNED, parentId, subQuestion));
            List<LocalExpansion.Touch> branch = RetrieveLocalContext.expand(graph, seedMatcher, subQuestion, corpusId,
                    effective.local());
            if (branch.isEmpty()) {
                branch = RetrieveLocalContext.expand(graph, seedMatcher, question, corpusId, effective.local());
            }
            for (LocalExpansion.Touch touch : branch) {
                steps.add(touch.step());
                union.putIfAbsent(touch.item().key(), touch);
            }
        }

        List<RetrievedItem> items = new ArrayList<>();
        for (LocalExpansion.Touch touch : union.values()) {
            items.add(touch.toRetrievedItem(items.size() + 1));
        }
        return new RetrievalResult(RetrievalResult.Mode.DRIFT, question, corpusId, RetrievalResult.Status.MATCHED,
                "", items, new RetrievalTrace("", steps), warnings);
    }

    private List<String> subQuestions(String question, List<Community> communities, DriftRetrievalOptions options,
                                      List<String> warnings) {
        List<String> derived;
        try {
            derived = llmPort.deriveDriftSubQuestions(question, communities);
        } catch (RuntimeException e) {
            if (options.failurePolicy() == FailurePolicy.FAIL_RUN) {
                throw e;
            }
            warnings.add("Deriving DRIFT sub-questions failed, the deterministic sub-questions were used: "
                    + e.getMessage());
            return LlmPort.none().deriveDriftSubQuestions(question, communities);
        }
        if (derived == null || derived.isEmpty()) {
            return LlmPort.none().deriveDriftSubQuestions(question, communities);
        }
        return derived.stream().filter(subQuestion -> subQuestion != null && !subQuestion.isBlank()).toList();
    }
}
