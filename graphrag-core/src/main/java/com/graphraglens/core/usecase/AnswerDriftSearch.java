package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Community;
import com.graphraglens.core.domain.GraphExtraction;
import com.graphraglens.core.domain.RetrievalStep;
import com.graphraglens.core.port.GraphStorePort;
import com.graphraglens.core.port.LlmPort;

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
 */
public class AnswerDriftSearch {

    private static final String NO_LOCAL_MATCH_ANSWER = LocalSearchAnswer.noMatch().answer();
    private static final LlmPort DEFAULT_LLM_PORT = corpus -> new GraphExtraction(List.of(), List.of());

    private final GraphStorePort graphStorePort;
    private final LlmPort llmPort;

    public AnswerDriftSearch(GraphStorePort graphStorePort, LlmPort llmPort) {
        this.graphStorePort = graphStorePort;
        this.llmPort = llmPort == null ? DEFAULT_LLM_PORT : llmPort;
    }

    public DriftSearchAnswer answer(String question) {
        return answer(question, null);
    }

    public DriftSearchAnswer answer(String question, String corpusId) {
        Collection<Community> communities = graphStorePort.communities(corpusId);
        if (communities == null || communities.isEmpty()) {
            return DriftSearchAnswer.noCommunitiesYet();
        }

        Set<String> tokens = KeywordMatcher.tokenize(question);
        List<RetrievalStep> steps = new ArrayList<>();
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
            return DriftSearchAnswer.matched(
                    "DRIFT searched the available Community summaries, but none of them clearly matched that question "
                            + "yet. Try asking about a named person, place, or event.",
                    steps);
        }

        int topScore = bestScore;
        List<Community> candidates = scoredCommunities.stream()
                .filter(candidate -> candidate.score() == topScore)
                .map(ScoredCommunity::community)
                .sorted(Comparator.comparing(Community::id))
                .toList();

        List<String> subQuestions = llmPort.deriveDriftSubQuestions(question, candidates);
        AnswerLocalSearch answerLocalSearch = new AnswerLocalSearch(graphStorePort);
        String synthesizedAnswer = null;

        for (String subQuestion : subQuestions) {
            LocalSearchAnswer subAnswer = answerLocalSearch.answer(subQuestion, corpusId);
            steps.addAll(subAnswer.steps());
            if (synthesizedAnswer == null && hasRelationshipHop(subAnswer)) {
                synthesizedAnswer = "DRIFT matched a relevant Community and then grounded the answer locally: "
                        + subAnswer.answer();
                break;
            }
        }

        if (synthesizedAnswer != null) {
            return DriftSearchAnswer.matched(synthesizedAnswer, steps);
        }

        return DriftSearchAnswer.matched(
                "DRIFT matched a relevant Community, but its spawned sub-question did not find a graph-grounded "
                        + "local match yet.",
                steps);
    }

    private record ScoredCommunity(Community community, int score) {
    }

    private static boolean hasRelationshipHop(LocalSearchAnswer answer) {
        if (answer == null || NO_LOCAL_MATCH_ANSWER.equals(answer.answer())) {
            return false;
        }
        return answer.steps().stream().anyMatch(step -> step.kind() == RetrievalStep.Kind.RELATIONSHIP);
    }
}
