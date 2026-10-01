package io.graphrag.core.usecase;

import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.RetrievalStep;
import io.graphrag.core.port.EmbeddingPort;
import io.graphrag.core.port.GraphStorePort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Answers a Local Search question by actually traversing the Knowledge
 * Graph — starting from the Entity that best matches the question's
 * keywords, then walking outward one hop via that Entity's own
 * Relationships to find the neighbor the question is most likely asking
 * about.
 *
 * <p>This replaces the earlier "best-effort" approach of independently
 * keyword-scoring every Relationship and every Entity in the whole corpus
 * and returning whichever single one scored highest — a flat scan, not a
 * traversal, and one whose Retrieval Trace steps didn't correspond to a
 * real path through the graph (deferred-work.md's own assessment). Here,
 * the Relationship search is scoped to only the seed Entity's own
 * Relationships, so the resulting trace is a genuine one-hop walk:
 * Entity → Relationship → Entity.
 *
 * <p>The Relationship step's {@code identifier} is deliberately built with
 * the exact same {@code sourceIdentity->type->targetIdentity} convention
 * {@code graph-canvas.js}'s {@code addRelationship()} uses for its rendered
 * edge ids, so Retrieval Trace Replay can resolve and highlight the real
 * edge on the canvas — not just the two Entities either side of it.
 */
public class AnswerLocalSearch {

    /** How many seed Entities the semantic path records as trace steps. */
    static final int SEMANTIC_SEED_COUNT = 3;

    private final GraphStorePort graphStorePort;
    private final EmbeddingPort embeddingPort;

    public AnswerLocalSearch(GraphStorePort graphStorePort) {
        this(graphStorePort, null);
    }

    /**
     * @param embeddingPort when semantic, seeds are the Entities closest in
     *                      meaning to the question (keyword fallback when the
     *                      corpus has no embeddings); null or a non-semantic
     *                      port keeps pure keyword matching
     */
    public AnswerLocalSearch(GraphStorePort graphStorePort, EmbeddingPort embeddingPort) {
        this.graphStorePort = graphStorePort;
        this.embeddingPort = embeddingPort;
    }

    public LocalSearchAnswer answer(String question, String corpusId) {
        Set<String> tokens = KeywordMatcher.tokenize(question);
        List<RetrievalStep> steps = new ArrayList<>();

        Entity seed = null;
        for (Entity similar : semanticSeeds(question, corpusId)) {
            if (seed == null) {
                seed = similar;
            }
            steps.add(new RetrievalStep(RetrievalStep.Kind.ENTITY, similar.normalizedIdentity(), similar.name()));
        }
        if (seed == null) {
            seed = bestMatchingEntity(orEmpty(graphStorePort.entities(corpusId)), tokens);
            if (seed == null) {
                return LocalSearchAnswer.noMatch();
            }
            steps.add(new RetrievalStep(RetrievalStep.Kind.ENTITY, seed.normalizedIdentity(), seed.name()));
        }

        Collection<Relationship> relationships = orEmpty(graphStorePort.relationships(corpusId));
        String seedIdentity = seed.normalizedIdentity();

        Relationship hop = bestMatchingHop(relationships, seedIdentity, tokens);
        if (hop == null) {
            return LocalSearchAnswer.matched(
                    "In this corpus graph, the closest local match is " + seed.name() + " (" + seed.type() + ").",
                    steps);
        }

        String sourceIdentity = Entity.identityOf(hop.source(), hop.sourceType());
        String targetIdentity = Entity.identityOf(hop.target(), hop.targetType());
        boolean seedIsSource = sourceIdentity.equals(seedIdentity);
        String otherIdentity = seedIsSource ? targetIdentity : sourceIdentity;
        String otherName = seedIsSource ? hop.target() : hop.source();

        String edgeId = sourceIdentity + "->" + hop.type() + "->" + targetIdentity;
        steps.add(new RetrievalStep(RetrievalStep.Kind.RELATIONSHIP, edgeId,
                hop.source() + " —" + hop.type().replace('_', ' ') + "→ " + hop.target()));
        steps.add(new RetrievalStep(RetrievalStep.Kind.ENTITY, otherIdentity, otherName));

        String answer = "In this corpus graph, " + hop.source() + " "
                + hop.type().replace('_', ' ').toLowerCase(Locale.ROOT) + " " + hop.target() + ".";
        return LocalSearchAnswer.matched(answer, steps);
    }

    /**
     * The top {@value #SEMANTIC_SEED_COUNT} Entities by meaning, most similar
     * first; empty without a semantic port or when the corpus has no
     * embedded Entities.
     */
    private List<Entity> semanticSeeds(String question, String corpusId) {
        if (!EmbedGraphElements.isSemantic(embeddingPort)) {
            return List.of();
        }
        List<Entity> similar;
        try {
            similar = graphStorePort.similarEntities(
                    corpusId, embeddingPort.embed(question == null ? "" : question), SEMANTIC_SEED_COUNT);
        } catch (RuntimeException e) {
            throw new SemanticMatchingException("Semantic Entity matching failed: " + e.getMessage(), e);
        }
        if (similar == null) {
            return List.of();
        }
        return similar.stream().filter(Objects::nonNull).limit(SEMANTIC_SEED_COUNT).toList();
    }

    private Entity bestMatchingEntity(Collection<Entity> entities, Set<String> tokens) {
        Entity best = null;
        int bestScore = -1;
        for (Entity entity : entities) {
            if (entity == null || entity.name() == null || entity.type() == null) {
                continue;
            }
            int score = KeywordMatcher.score(entity.name() + " " + entity.type(), tokens);
            if (score > bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return bestScore > 0 ? best : null;
    }

    /**
     * Scores only Relationships that actually touch {@code seedIdentity} —
     * a one-hop walk outward from the seed, never an independent scan of
     * every Relationship in the corpus.
     */
    private Relationship bestMatchingHop(Collection<Relationship> relationships, String seedIdentity, Set<String> tokens) {
        Relationship best = null;
        int bestScore = -1;
        for (Relationship relationship : relationships) {
            if (relationship == null) {
                continue;
            }
            String sourceIdentity = Entity.identityOf(relationship.source(), relationship.sourceType());
            String targetIdentity = Entity.identityOf(relationship.target(), relationship.targetType());
            if (!sourceIdentity.equals(seedIdentity) && !targetIdentity.equals(seedIdentity)) {
                continue;
            }
            String relationText = (relationship.source() + " " + relationship.type() + " " + relationship.target())
                    .replace('_', ' ');
            int score = KeywordMatcher.score(relationText, tokens);
            if (score > bestScore) {
                bestScore = score;
                best = relationship;
            }
        }
        return bestScore > 0 ? best : null;
    }

    private static <T> Collection<T> orEmpty(Collection<T> collection) {
        return collection == null ? List.of() : collection;
    }
}
