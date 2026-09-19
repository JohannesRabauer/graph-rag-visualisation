package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Community;
import com.graphraglens.core.port.GraphStorePort;

import java.util.Collection;
import java.util.Set;

/**
 * Answers a Global Search question by aggregating already-persisted
 * Community summaries.
 *
 * <p>This use case only reads {@link GraphStorePort#communities()} — it
 * never triggers {@link DetectCommunities} or summary generation itself
 * (AD-6). Communities are scored against the question the same
 * keyword-overlap way Local Search already scores sentences, so the demo
 * stays deterministic and provider-agnostic.
 *
 * <p>{@link GraphStorePort#communities()} is a process-global, unscoped
 * store: it is not filtered by any particular corpus. A Global Search
 * answer can therefore be drawn from a Community that belongs to a
 * different corpus than the one named in the request. This is an
 * intentionally accepted, pre-existing limitation (same as Story 4.3), not
 * a bug in this class.
 */
public class AnswerGlobalSearch {

    private final GraphStorePort graphStorePort;

    public AnswerGlobalSearch(GraphStorePort graphStorePort) {
        this.graphStorePort = graphStorePort;
    }

    public GlobalSearchAnswer answer(String question) {
        Collection<Community> communities = graphStorePort.communities();
        if (communities == null || communities.isEmpty()) {
            return GlobalSearchAnswer.noCommunitiesYet();
        }

        Set<String> tokens = KeywordMatcher.tokenize(question);
        Community best = null;
        int bestScore = -1;
        for (Community community : communities) {
            int score = KeywordMatcher.score(community.summary(), tokens);
            if (score > bestScore
                    || (score == bestScore && best != null && community.id().compareTo(best.id()) < 0)) {
                bestScore = score;
                best = community;
            }
        }

        if (best != null && bestScore > 0) {
            return GlobalSearchAnswer.matched(
                    "Across the corpus, the strongest signal is that " + best.summary());
        }

        return GlobalSearchAnswer.matched(
                "The corpus has Community summaries, but none of them clearly match that question yet. "
                        + "Try asking about a named person, place, or event.");
    }
}
