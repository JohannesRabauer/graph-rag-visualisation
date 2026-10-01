package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Citation;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.SynthesizedAnswer;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.LlmPort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Answers a DRIFT Search question by first scoring Community summaries,
 * then spawning targeted Local Search sub-questions from the best-matching
 * Communities, and finally synthesizing one answer from the resulting local
 * traversals.
 *
 * <p>With a semantic {@link EmbeddingPort}, the candidates are the
 * {@value #SEMANTIC_COMMUNITY_COUNT} Communities closest in meaning to the
 * question (most similar first, one {@code COMMUNITY} step each), and the
 * nested Local Searches seed by meaning too. When the corpus has no Community
 * embeddings, the keyword candidates are used for that query.
 *
 * <p>Story 15.3: with an answer-synthesizing {@link LlmPort}
 * ({@link LlmPort#synthesizesAnswers()}), each sub-question branch gathers a
 * Local-style context ({@link LocalContextAssembler}) under its
 * {@code SUB_QUESTION_SPAWNED} step, without an LLM call of its own. One
 * synthesis then runs over the candidate Community summaries plus the union
 * of the branch contexts (de-duplicated, numbered in first-seen order) and is
 * recorded as the single {@code SYNTHESIS} step after the last branch.
 * Without such a port the templated path runs exactly as before.
 */
public class AnswerDriftSearch {

    private static final String NO_LOCAL_MATCH_ANSWER = LocalSearchAnswer.noMatch().answer();
    private static final LlmPort DEFAULT_LLM_PORT = corpus -> new GraphExtraction(List.of(), List.of());

    /** How many candidate Communities the semantic path uses. */
    static final int SEMANTIC_COMMUNITY_COUNT = 3;
    /** Story 15.3: candidate Communities the synthesizing path branches from, in candidate order. */
    static final int SYNTHESIS_CANDIDATE_COUNT = 3;
    static final String NO_BRANCH_MATCH_ANSWER = "DRIFT matched a relevant Community, but its spawned "
            + "sub-question did not find a graph-grounded local match yet.";
    static final String NOT_IN_CONTEXT_REASON =
            "The Community summaries, graph facts and passages DRIFT retrieved for this question do not answer it. "
                    + "Try asking about a named person, place, or event.";

    private final GraphStorePort graphStorePort;
    private final LlmPort llmPort;
    private final EmbeddingPort embeddingPort;

    public AnswerDriftSearch(GraphStorePort graphStorePort, LlmPort llmPort) {
        this(graphStorePort, llmPort, null);
    }

    public AnswerDriftSearch(GraphStorePort graphStorePort, LlmPort llmPort, EmbeddingPort embeddingPort) {
        this.graphStorePort = graphStorePort;
        this.llmPort = llmPort == null ? DEFAULT_LLM_PORT : llmPort;
        this.embeddingPort = embeddingPort;
    }

    public DriftSearchAnswer answer(String question) {
        return answer(question, null);
    }

    public DriftSearchAnswer answer(String question, String corpusId) {
        Collection<Community> communities = graphStorePort.communities(corpusId);
        if (communities == null || communities.isEmpty()) {
            return DriftSearchAnswer.noCommunitiesYet();
        }

        List<RetrievalStep> steps = new ArrayList<>();
        List<Community> candidates = AnswerGlobalSearch.similarCommunities(
                graphStorePort, embeddingPort, question, corpusId, SEMANTIC_COMMUNITY_COUNT);
        if (!candidates.isEmpty()) {
            for (Community community : candidates) {
                steps.add(new RetrievalStep(RetrievalStep.Kind.COMMUNITY, community.id(), community.summary()));
            }
        } else {
            Set<String> tokens = KeywordMatcher.tokenize(question);
            List<ScoredCommunity> scoredCommunities = new ArrayList<>();
            int bestScore = -1;

            for (Community community : communities) {
                steps.add(new RetrievalStep(RetrievalStep.Kind.COMMUNITY, community.id(), community.summary()));
                int score = KeywordMatcher.score(community.summary(), tokens);
                scoredCommunities.add(new ScoredCommunity(community, score));
                if (score > bestScore) {
                    bestScore = score;
                }
            }

            if (bestScore <= 0) {
                return DriftSearchAnswer.noViableSubQuestions(steps);
            }

            int topScore = bestScore;
            candidates = scoredCommunities.stream()
                    .filter(candidate -> candidate.score() == topScore)
                    .map(ScoredCommunity::community)
                    .sorted(Comparator.comparing(Community::id))
                    .toList();
        }

        if (llmPort.synthesizesAnswers()) {
            // Story 15.3: bound the branches and the synthesis context (keyword ties are unbounded).
            List<Community> capped = candidates.stream().limit(SYNTHESIS_CANDIDATE_COUNT).toList();
            return synthesizedAnswer(question, corpusId, capped,
                    llmPort.deriveDriftSubQuestions(question, capped), steps);
        }
        List<String> subQuestions = llmPort.deriveDriftSubQuestions(question, candidates);
        AnswerLocalSearch answerLocalSearch = new AnswerLocalSearch(graphStorePort, embeddingPort);
        List<BranchAnswer> branchAnswers = new ArrayList<>();

        for (int i = 0; i < subQuestions.size(); i++) {
            String subQuestion = subQuestions.get(i);
            String parentId = i < candidates.size() ? candidates.get(i).id() : "";
            steps.add(new RetrievalStep(RetrievalStep.Kind.SUB_QUESTION_SPAWNED, parentId, subQuestion));
            LocalSearchAnswer subAnswer = answerLocalSearch.answer(subQuestion, corpusId);
            branchAnswers.add(new BranchAnswer(parentId, subAnswer));
            steps.addAll(subAnswer.steps());
        }

        for (BranchAnswer branchAnswer : branchAnswers) {
            if (hasRelationshipHop(branchAnswer.answer())) {
                String synthesizedAnswer = "DRIFT matched a relevant Community and then grounded the answer locally: "
                        + branchAnswer.answer().answer();
                steps.add(new RetrievalStep(RetrievalStep.Kind.SYNTHESIS, branchAnswer.parentId(), synthesizedAnswer));
                return DriftSearchAnswer.matched(synthesizedAnswer, steps);
            }
        }

        return noBranchMatch(steps);
    }

    private static DriftSearchAnswer noBranchMatch(List<RetrievalStep> steps) {
        steps.add(new RetrievalStep(RetrievalStep.Kind.SYNTHESIS, "", NO_BRANCH_MATCH_ANSWER));
        return DriftSearchAnswer.matched(NO_BRANCH_MATCH_ANSWER, steps);
    }

    /**
     * Story 15.3: one Local-style context per branch (recorded under its
     * {@code SUB_QUESTION_SPAWNED} step), then one synthesis over the
     * candidate summaries and the de-duplicated union of branch items.
     */
    private DriftSearchAnswer synthesizedAnswer(String question, String corpusId, List<Community> candidates,
                                                List<String> subQuestions, List<RetrievalStep> steps) {
        LocalContextAssembler assembler = new LocalContextAssembler(graphStorePort, embeddingPort);
        Map<String, LocalContextAssembler.Item> union = new LinkedHashMap<>();
        for (Community community : candidates) {
            union.putIfAbsent("COMMUNITY:" + community.id(), new LocalContextAssembler.Item(
                    "COMMUNITY:" + community.id(), RetrievalStep.Kind.COMMUNITY,
                    AnswerGlobalSearch.communityText(community), null));
        }
        Map<String, Citation> citationsByUnit = new LinkedHashMap<>();
        boolean anyBranchContext = false;

        for (int i = 0; i < subQuestions.size(); i++) {
            String subQuestion = subQuestions.get(i);
            String parentId = i < candidates.size() ? candidates.get(i).id() : "";
            steps.add(new RetrievalStep(RetrievalStep.Kind.SUB_QUESTION_SPAWNED, parentId, subQuestion));
            Optional<LocalContextAssembler.Assembly> branch = assembler.assemble(subQuestion, corpusId);
            if (branch.isEmpty()) {
                continue;
            }
            anyBranchContext = true;
            steps.addAll(branch.get().steps());
            for (LocalContextAssembler.Item item : branch.get().items()) {
                union.putIfAbsent(item.key(), item);
            }
            branch.get().citationsByUnit().forEach(citationsByUnit::putIfAbsent);
        }

        if (!anyBranchContext) {
            return noBranchMatch(steps);
        }

        List<ContextItem> context = LocalContextAssembler.number(new ArrayList<>(union.values()));
        SynthesizedAnswer synthesized = llmPort.synthesizeAnswer(question, context);
        if (LocalContextAssembler.isNotInContext(synthesized)) {
            return DriftSearchAnswer.notInContext(NOT_IN_CONTEXT_REASON, steps);
        }
        CitationResolver.Resolution resolution =
                CitationResolver.resolve(synthesized.text(), context, citationsByUnit);
        if (resolution.text().isBlank()) {
            return DriftSearchAnswer.notInContext(NOT_IN_CONTEXT_REASON, steps);
        }
        String parentId = candidates.isEmpty() ? "" : candidates.getFirst().id();
        steps.add(new RetrievalStep(RetrievalStep.Kind.SYNTHESIS, parentId, resolution.text()));
        return DriftSearchAnswer.synthesized(resolution.text(), steps, resolution.citations());
    }

    private record ScoredCommunity(Community community, int score) {
    }

    private record BranchAnswer(String parentId, LocalSearchAnswer answer) {
    }

    private static boolean hasRelationshipHop(LocalSearchAnswer answer) {
        if (answer == null || NO_LOCAL_MATCH_ANSWER.equals(answer.answer())) {
            return false;
        }
        return answer.steps().stream().anyMatch(step -> step.kind() == RetrievalStep.Kind.RELATIONSHIP);
    }
}
