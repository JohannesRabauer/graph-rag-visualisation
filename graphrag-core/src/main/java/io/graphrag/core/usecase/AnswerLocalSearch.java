package io.graphrag.core.usecase;

import io.graphrag.core.domain.Citation;
import io.graphrag.core.domain.ContextItem;
import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.RetrievalStep;
import io.graphrag.core.domain.SynthesizedAnswer;
import io.graphrag.core.domain.TextUnit;
import io.graphrag.core.port.EmbeddingPort;
import io.graphrag.core.port.GraphStorePort;
import io.graphrag.core.port.LlmPort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 *
 * <p>Story 15.2: with an answer-synthesizing {@link LlmPort}
 * ({@link LlmPort#synthesizesAnswers()}), the answer is generated instead of
 * templated. A bounded context is assembled — the seeds, up to
 * {@value #MAX_CONTEXT_RELATIONSHIPS} Relationships touching them (highest
 * weight first) and up to {@value #MAX_CONTEXT_TEXT_UNITS} Text Units those
 * cite — each item recorded as a trace step as it is added. The model's
 * inline {@code [n]} citations are resolved by {@link CitationResolver}, so
 * every citation is a {@code TEXT_UNIT} step of the same trace. Without such
 * a port the templated path below runs exactly as before.
 */
public class AnswerLocalSearch {

    /** How many seed Entities the semantic path records as trace steps. */
    static final int SEMANTIC_SEED_COUNT = 3;
    /** Story 15.2: Relationships touching a seed that enter the synthesis context. */
    static final int MAX_CONTEXT_RELATIONSHIPS = 10;
    /** Story 15.2: cited Text Units that enter the synthesis context. */
    static final int MAX_CONTEXT_TEXT_UNITS = 5;
    static final int EXCERPT_CHARS = 200;
    static final String NOT_IN_CONTEXT_REASON =
            "The passages and graph facts retrieved for this question do not answer it. "
                    + "Try asking about a named entity or relationship visible in the graph.";

    private final GraphStorePort graphStorePort;
    private final EmbeddingPort embeddingPort;
    private final LlmPort llmPort;

    public AnswerLocalSearch(GraphStorePort graphStorePort) {
        this(graphStorePort, null, null);
    }

    /**
     * @param embeddingPort when semantic, seeds are the Entities closest in
     *                      meaning to the question (keyword fallback when the
     *                      corpus has no embeddings); null or a non-semantic
     *                      port keeps pure keyword matching
     */
    public AnswerLocalSearch(GraphStorePort graphStorePort, EmbeddingPort embeddingPort) {
        this(graphStorePort, embeddingPort, null);
    }

    /**
     * @param llmPort when {@link LlmPort#synthesizesAnswers()} (Story 15.2), the
     *                answer is generated from a cited context; null or a
     *                non-synthesizing port keeps the templated answer
     */
    public AnswerLocalSearch(GraphStorePort graphStorePort, EmbeddingPort embeddingPort, LlmPort llmPort) {
        this.graphStorePort = graphStorePort;
        this.embeddingPort = embeddingPort;
        this.llmPort = llmPort;
    }

    public LocalSearchAnswer answer(String question, String corpusId) {
        if (llmPort != null && llmPort.synthesizesAnswers()) {
            return synthesizedAnswer(question, corpusId);
        }
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
     * Story 15.2: assembles the bounded context (seeds, Relationships, Text
     * Units — each recorded as a step as it is added), asks the LLM, and
     * resolves its citations.
     */
    private LocalSearchAnswer synthesizedAnswer(String question, String corpusId) {
        List<Entity> seeds = new ArrayList<>(semanticSeeds(question, corpusId));
        if (seeds.isEmpty()) {
            Entity keywordSeed = bestMatchingEntity(orEmpty(graphStorePort.entities(corpusId)),
                    KeywordMatcher.tokenize(question));
            if (keywordSeed == null) {
                return LocalSearchAnswer.noMatch();
            }
            seeds.add(keywordSeed);
        }

        List<RetrievalStep> steps = new ArrayList<>();
        List<ContextItem> context = new ArrayList<>();

        Set<String> seedIdentities = new LinkedHashSet<>();
        for (Entity seed : seeds) {
            seedIdentities.add(seed.normalizedIdentity());
            steps.add(new RetrievalStep(RetrievalStep.Kind.ENTITY, seed.normalizedIdentity(), seed.name()));
            context.add(new ContextItem(context.size() + 1, RetrievalStep.Kind.ENTITY, entityText(seed), null));
        }

        List<Relationship> touching = new ArrayList<>();
        for (Relationship relationship : orEmpty(graphStorePort.relationships(corpusId))) {
            if (relationship == null) {
                continue;
            }
            String sourceIdentity = Entity.identityOf(relationship.source(), relationship.sourceType());
            String targetIdentity = Entity.identityOf(relationship.target(), relationship.targetType());
            if (seedIdentities.contains(sourceIdentity) || seedIdentities.contains(targetIdentity)) {
                touching.add(relationship);
            }
        }
        // List.sort is stable, so equal weights keep their stored order.
        touching.sort(Comparator.comparingInt(Relationship::weight).reversed());
        List<Relationship> included = touching.subList(0, Math.min(MAX_CONTEXT_RELATIONSHIPS, touching.size()));
        for (Relationship relationship : included) {
            steps.add(relationshipStep(relationship));
            context.add(new ContextItem(context.size() + 1, RetrievalStep.Kind.RELATIONSHIP,
                    relationshipText(relationship), null));
        }

        // Rank cited Text Units: +weight per included Relationship, +1 per seed; ties keep first-seen order.
        Map<String, Integer> scoreByUnit = new LinkedHashMap<>();
        for (Entity seed : seeds) {
            for (String unitId : seed.sourceTextUnitIds()) {
                scoreByUnit.merge(unitId, 1, Integer::sum);
            }
        }
        for (Relationship relationship : included) {
            for (String unitId : relationship.sourceTextUnitIds()) {
                scoreByUnit.merge(unitId, relationship.weight(), Integer::sum);
            }
        }
        List<String> rankedUnits = new ArrayList<>(scoreByUnit.keySet());
        rankedUnits.sort(Comparator.comparingInt((String unitId) -> scoreByUnit.get(unitId)).reversed());

        Map<String, Citation> citationsByUnit = new LinkedHashMap<>();
        for (String unitId : rankedUnits) {
            if (citationsByUnit.size() >= MAX_CONTEXT_TEXT_UNITS) {
                break;
            }
            Optional<TextUnit> loaded = loadTextUnit(corpusId, unitId);
            if (loaded.isEmpty() || loaded.get().text() == null) {
                continue;
            }
            TextUnit unit = loaded.get();
            String excerpt = excerpt(unit.text());
            citationsByUnit.put(unitId, new Citation(unitId, unit.documentName(), excerpt));
            steps.add(new RetrievalStep(RetrievalStep.Kind.TEXT_UNIT, unitId, excerpt));
            context.add(new ContextItem(context.size() + 1, RetrievalStep.Kind.TEXT_UNIT, unit.text(), unitId));
        }

        SynthesizedAnswer synthesized = llmPort.synthesizeAnswer(question, List.copyOf(context));
        if (synthesized == null || synthesized.notInContext() || synthesized.text().isBlank()
                || SynthesizedAnswer.isNotInContextSentinel(synthesized.text())) {
            return LocalSearchAnswer.notInContext(NOT_IN_CONTEXT_REASON, steps);
        }
        CitationResolver.Resolution resolution =
                CitationResolver.resolve(synthesized.text(), context, citationsByUnit);
        if (resolution.text().isBlank()) {
            return LocalSearchAnswer.notInContext(NOT_IN_CONTEXT_REASON, steps);
        }
        return LocalSearchAnswer.synthesized(resolution.text(), steps, resolution.citations());
    }

    /** A missing Text Unit is skipped; a store failure propagates (FR-5). */
    private Optional<TextUnit> loadTextUnit(String corpusId, String unitId) {
        Optional<TextUnit> loaded = graphStorePort.textUnit(corpusId, unitId);
        return loaded == null ? Optional.empty() : loaded;
    }

    private static RetrievalStep relationshipStep(Relationship relationship) {
        String edgeId = Entity.identityOf(relationship.source(), relationship.sourceType())
                + "->" + relationship.type() + "->"
                + Entity.identityOf(relationship.target(), relationship.targetType());
        return new RetrievalStep(RetrievalStep.Kind.RELATIONSHIP, edgeId,
                relationship.source() + " —" + relationship.type().replace('_', ' ') + "→ " + relationship.target());
    }

    private static String entityText(Entity entity) {
        String text = entity.name() + " (" + entity.type() + ")";
        return entity.description().isEmpty() ? text : text + ": " + entity.description();
    }

    private static String relationshipText(Relationship relationship) {
        String text = relationship.source() + " -[" + relationship.type() + "]-> " + relationship.target();
        return relationship.description().isEmpty() ? text : text + ": " + relationship.description();
    }

    /** The first {@value #EXCERPT_CHARS} characters, whitespace-collapsed, with "…" when cut. */
    static String excerpt(String passage) {
        String collapsed = passage == null ? "" : passage.trim().replaceAll("\\s+", " ");
        return collapsed.length() <= EXCERPT_CHARS ? collapsed : collapsed.substring(0, EXCERPT_CHARS) + "…";
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
