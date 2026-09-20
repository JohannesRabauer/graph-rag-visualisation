package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.Relationship;
import com.graphraglens.core.domain.RetrievalStep;
import com.graphraglens.core.port.GraphStorePort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
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

    private final GraphStorePort graphStorePort;

    public AnswerLocalSearch(GraphStorePort graphStorePort) {
        this.graphStorePort = graphStorePort;
    }

    public LocalSearchAnswer answer(String question, String corpusId) {
        Set<String> tokens = KeywordMatcher.tokenize(question);
        Collection<Entity> entities = orEmpty(graphStorePort.entities(corpusId));
        Collection<Relationship> relationships = orEmpty(graphStorePort.relationships(corpusId));

        Entity seed = bestMatchingEntity(entities, tokens);
        if (seed == null) {
            return LocalSearchAnswer.noMatch();
        }

        String seedIdentity = seed.normalizedIdentity();
        List<RetrievalStep> steps = new ArrayList<>();
        steps.add(new RetrievalStep(RetrievalStep.Kind.ENTITY, seedIdentity, seed.name()));

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
