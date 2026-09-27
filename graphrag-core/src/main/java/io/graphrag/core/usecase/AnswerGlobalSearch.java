package io.graphrag.core.usecase;

import io.graphrag.core.domain.Community;
import io.graphrag.core.domain.RetrievalStep;
import io.graphrag.core.port.GraphStorePort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Answers a Global Search question by aggregating already-persisted
 * Community summaries.
 *
 * <p>This use case only reads {@link GraphStorePort#communities(String)} — it
 * never triggers {@link DetectCommunities} or summary generation itself
 * (AD-6). Communities are scored against the question the same
 * keyword-overlap way Local Search already scores sentences, so the demo
 * stays deterministic and provider-agnostic.
 *
 * <p>Global Search reads only Communities tied to the selected corpus.
 */
public class AnswerGlobalSearch {

    private final GraphStorePort graphStorePort;

    public AnswerGlobalSearch(GraphStorePort graphStorePort) {
        this.graphStorePort = graphStorePort;
    }

    public GlobalSearchAnswer answer(String question) {
        return answer(question, null);
    }

    public GlobalSearchAnswer answer(String question, String corpusId) {
        Collection<Community> communities = graphStorePort.communities(corpusId);
        if (communities == null || communities.isEmpty()) {
            return GlobalSearchAnswer.noCommunitiesYet();
        }

        Set<String> tokens = KeywordMatcher.tokenize(question);
        Community best = null;
        int bestScore = -1;
        List<RetrievalStep> steps = new ArrayList<>();
        for (Community community : communities) {
            steps.add(new RetrievalStep(RetrievalStep.Kind.COMMUNITY, community.id(), community.summary()));
            int score = KeywordMatcher.score(community.summary(), tokens);
            if (score > bestScore
                    || (score == bestScore && best != null && community.id().compareTo(best.id()) < 0)) {
                bestScore = score;
                best = community;
            }
        }

        if (best != null && bestScore > 0) {
            return GlobalSearchAnswer.matched(
                    "Across the corpus, the strongest signal is that " + best.summary(), steps);
        }

        return GlobalSearchAnswer.matched(
                "The corpus has Community summaries, but none of them clearly match that question yet. "
                        + "Try asking about a named person, place, or event.",
                steps);
    }
}
