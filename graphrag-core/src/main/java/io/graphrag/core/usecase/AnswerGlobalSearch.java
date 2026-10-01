package io.graphrag.core.usecase;

import io.graphrag.core.domain.Community;
import io.graphrag.core.domain.RetrievalStep;
import io.graphrag.core.port.EmbeddingPort;
import io.graphrag.core.port.GraphStorePort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
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
 *
 * <p>With a semantic {@link EmbeddingPort}, the {@value #SEMANTIC_COMMUNITY_COUNT}
 * Communities closest in meaning to the question are recorded instead (most
 * similar first) and the answer comes from the first one. When the corpus has
 * no Community embeddings, the keyword path above is used for that query.
 */
public class AnswerGlobalSearch {

    /** How many Communities the semantic path records as trace steps. */
    static final int SEMANTIC_COMMUNITY_COUNT = 3;

    private final GraphStorePort graphStorePort;
    private final EmbeddingPort embeddingPort;

    public AnswerGlobalSearch(GraphStorePort graphStorePort) {
        this(graphStorePort, null);
    }

    public AnswerGlobalSearch(GraphStorePort graphStorePort, EmbeddingPort embeddingPort) {
        this.graphStorePort = graphStorePort;
        this.embeddingPort = embeddingPort;
    }

    public GlobalSearchAnswer answer(String question) {
        return answer(question, null);
    }

    public GlobalSearchAnswer answer(String question, String corpusId) {
        Collection<Community> communities = graphStorePort.communities(corpusId);
        if (communities == null || communities.isEmpty()) {
            return GlobalSearchAnswer.noCommunitiesYet();
        }

        List<Community> similar = similarCommunities(graphStorePort, embeddingPort, question, corpusId,
                SEMANTIC_COMMUNITY_COUNT);
        if (!similar.isEmpty()) {
            List<RetrievalStep> semanticSteps = new ArrayList<>();
            for (Community community : similar) {
                semanticSteps.add(new RetrievalStep(RetrievalStep.Kind.COMMUNITY, community.id(), community.summary()));
            }
            return GlobalSearchAnswer.matched(
                    "Across the corpus, the strongest signal is that " + similar.getFirst().summary(), semanticSteps);
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

    /**
     * The top {@code k} Communities by meaning, most similar first; empty
     * without a semantic port or when the corpus has no embedded Communities.
     */
    static List<Community> similarCommunities(GraphStorePort graphStorePort, EmbeddingPort embeddingPort,
                                              String question, String corpusId, int k) {
        if (!EmbedGraphElements.isSemantic(embeddingPort)) {
            return List.of();
        }
        List<Community> similar;
        try {
            similar = graphStorePort.similarCommunities(
                    corpusId, embeddingPort.embed(question == null ? "" : question), k);
        } catch (RuntimeException e) {
            throw new SemanticMatchingException("Semantic Community matching failed: " + e.getMessage(), e);
        }
        if (similar == null) {
            return List.of();
        }
        return similar.stream().filter(Objects::nonNull).limit(k).toList();
    }
}
