package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Community;
import com.graphraglens.core.port.GraphStorePort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Answers a Global Search question by aggregating already-persisted
 * Community summaries.
 *
 * <p>This use case only reads {@link GraphStorePort#communities()} — it
 * never triggers {@link DetectCommunities} or summary generation itself
 * (AD-6). Communities are scored against the question the same
 * keyword-overlap way Local Search already scores sentences, so the demo
 * stays deterministic and provider-agnostic.
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

        List<String> tokens = tokenize(question);
        Community best = null;
        int bestScore = -1;
        for (Community community : communities) {
            int score = score(community.summary(), tokens);
            if (score > bestScore) {
                bestScore = score;
                best = community;
            }
        }

        if (best != null && bestScore > 0) {
            return GlobalSearchAnswer.matched(
                    "Across the corpus, the strongest signal is that " + best.summary());
        }

        return GlobalSearchAnswer.noClearMatch(
                "The corpus has Community summaries, but none of them clearly match that question yet. "
                        + "Try asking about a named person, place, or event.");
    }

    private List<String> tokenize(String question) {
        List<String> tokens = new ArrayList<>();
        String lowerQuestion = question == null ? "" : question.toLowerCase(Locale.ROOT);
        for (String token : lowerQuestion.split("[^a-z0-9]+")) {
            if (!token.isBlank() && token.length() > 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private int score(String summary, List<String> tokens) {
        if (summary == null) {
            return 0;
        }
        String lowered = summary.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : tokens) {
            if (lowered.contains(token)) {
                score += 2;
            }
        }
        return score;
    }
}
