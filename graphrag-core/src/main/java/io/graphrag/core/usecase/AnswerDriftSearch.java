package io.graphrag.core.usecase;

import io.graphrag.core.domain.Community;
import io.graphrag.core.domain.GraphExtraction;
import io.graphrag.core.domain.RetrievalStep;
import io.graphrag.core.port.EmbeddingPort;
import io.graphrag.core.port.GraphStorePort;
import io.graphrag.core.port.LlmPort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
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
 */
public class AnswerDriftSearch {

    private static final String NO_LOCAL_MATCH_ANSWER = LocalSearchAnswer.noMatch().answer();
    private static final LlmPort DEFAULT_LLM_PORT = corpus -> new GraphExtraction(List.of(), List.of());

    /** How many candidate Communities the semantic path uses. */
    static final int SEMANTIC_COMMUNITY_COUNT = 3;

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

        String synthesizedAnswer = "DRIFT matched a relevant Community, but its spawned sub-question did not find a "
                + "graph-grounded local match yet.";
        steps.add(new RetrievalStep(RetrievalStep.Kind.SYNTHESIS, "", synthesizedAnswer));
        return DriftSearchAnswer.matched(
                synthesizedAnswer,
                steps);
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
