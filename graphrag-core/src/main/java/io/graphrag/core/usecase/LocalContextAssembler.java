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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Assembles the bounded, Local-style synthesis context of Story 15.2 — the
 * seeds, up to {@value #MAX_CONTEXT_RELATIONSHIPS} Relationships touching
 * them (highest weight first) and up to {@value #MAX_CONTEXT_TEXT_UNITS} Text
 * Units those cite — recording a trace step per item as it is added.
 *
 * <p>Shared by {@link AnswerLocalSearch} (one assembly, one synthesis) and
 * {@link AnswerDriftSearch} (one assembly per sub-question branch, one
 * synthesis over their union, Story 15.3). Also holds the small helpers the
 * synthesizing paths of all three modes share: Text Unit loading, excerpts
 * and the not-in-context normalization.
 */
final class LocalContextAssembler {

    /** How many seed Entities the semantic path records as trace steps. */
    static final int SEMANTIC_SEED_COUNT = 3;
    /** Relationships touching a seed that enter the synthesis context. */
    static final int MAX_CONTEXT_RELATIONSHIPS = 10;
    /** Cited Text Units that enter the synthesis context. */
    static final int MAX_CONTEXT_TEXT_UNITS = 5;
    static final int EXCERPT_CHARS = 200;

    private final GraphStorePort graphStorePort;
    private final EmbeddingPort embeddingPort;

    LocalContextAssembler(GraphStorePort graphStorePort, EmbeddingPort embeddingPort) {
        this.graphStorePort = graphStorePort;
        this.embeddingPort = embeddingPort;
    }

    /**
     * One context item before numbering. {@code key} identifies it across
     * assemblies (entity identity, edge id or Text Unit id, prefixed by kind),
     * so a union can de-duplicate.
     */
    record Item(String key, RetrievalStep.Kind kind, String text, String textUnitId) {
    }

    /** The steps recorded, the un-numbered items, and the citation per included Text Unit. */
    record Assembly(List<RetrievalStep> steps, List<Item> items, Map<String, Citation> citationsByUnit) {
    }

    /**
     * @return the assembled context, or empty when no seed Entity matches the
     *         question (semantically or by keyword)
     */
    Optional<Assembly> assemble(String question, String corpusId) {
        List<Entity> seeds = new ArrayList<>(semanticSeeds(question, corpusId));
        if (seeds.isEmpty()) {
            Entity keywordSeed = bestMatchingEntity(orEmpty(graphStorePort.entities(corpusId)),
                    KeywordMatcher.tokenize(question));
            if (keywordSeed == null) {
                return Optional.empty();
            }
            seeds.add(keywordSeed);
        }

        List<RetrievalStep> steps = new ArrayList<>();
        List<Item> items = new ArrayList<>();

        Set<String> seedIdentities = new LinkedHashSet<>();
        for (Entity seed : seeds) {
            seedIdentities.add(seed.normalizedIdentity());
            steps.add(new RetrievalStep(RetrievalStep.Kind.ENTITY, seed.normalizedIdentity(), seed.name()));
            items.add(new Item("ENTITY:" + seed.normalizedIdentity(), RetrievalStep.Kind.ENTITY,
                    entityText(seed), null));
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
            RetrievalStep step = relationshipStep(relationship);
            steps.add(step);
            items.add(new Item("RELATIONSHIP:" + step.identifier(), RetrievalStep.Kind.RELATIONSHIP,
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
            addTextUnit(graphStorePort, corpusId, unitId, steps, items, citationsByUnit);
        }
        return Optional.of(new Assembly(steps, items, citationsByUnit));
    }

    /**
     * Loads {@code unitId} and, when it has text, records its {@code TEXT_UNIT}
     * step, item and citation.
     *
     * @return the loaded unit, or null when it is missing or has no text
     */
    static TextUnit addTextUnit(GraphStorePort graphStorePort, String corpusId, String unitId,
                                List<RetrievalStep> steps, List<Item> items, Map<String, Citation> citationsByUnit) {
        Optional<TextUnit> loaded = loadTextUnit(graphStorePort, corpusId, unitId);
        if (loaded.isEmpty() || loaded.get().text() == null) {
            return null;
        }
        TextUnit unit = loaded.get();
        String excerpt = excerpt(unit.text());
        citationsByUnit.put(unitId, new Citation(unitId, unit.documentName(), excerpt));
        steps.add(new RetrievalStep(RetrievalStep.Kind.TEXT_UNIT, unitId, excerpt));
        items.add(new Item("TEXT_UNIT:" + unitId, RetrievalStep.Kind.TEXT_UNIT, unit.text(), unitId));
        return unit;
    }

    /** Numbers {@code items} {@code 1..n} in order. */
    static List<ContextItem> number(List<Item> items) {
        List<ContextItem> context = new ArrayList<>();
        for (Item item : items) {
            context.add(new ContextItem(context.size() + 1, item.kind(), item.text(), item.textUnitId()));
        }
        return List.copyOf(context);
    }

    /**
     * Whether a synthesized answer means "the context does not answer the
     * question": null, flagged, blank, or the normalized sentinel.
     */
    static boolean isNotInContext(SynthesizedAnswer synthesized) {
        return synthesized == null || synthesized.notInContext() || synthesized.text().isBlank()
                || SynthesizedAnswer.isNotInContextSentinel(synthesized.text());
    }

    /** A missing Text Unit is skipped; a store failure propagates (FR-5). */
    static Optional<TextUnit> loadTextUnit(GraphStorePort graphStorePort, String corpusId, String unitId) {
        Optional<TextUnit> loaded = graphStorePort.textUnit(corpusId, unitId);
        return loaded == null ? Optional.empty() : loaded;
    }

    static RetrievalStep relationshipStep(Relationship relationship) {
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
    List<Entity> semanticSeeds(String question, String corpusId) {
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

    static Entity bestMatchingEntity(Collection<Entity> entities, Set<String> tokens) {
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

    static <T> Collection<T> orEmpty(Collection<T> collection) {
        return collection == null ? List.of() : collection;
    }
}
