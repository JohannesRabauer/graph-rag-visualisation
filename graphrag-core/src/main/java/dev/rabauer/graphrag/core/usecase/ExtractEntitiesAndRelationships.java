package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.LlmPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Executes the knowledge-graph extraction pass for a corpus, one Text Unit
 * at a time (AD-24): the corpus is split by {@link TextUnitSplitter}, each
 * unit is extracted against {@link EntityTypes#ALL}, its types are
 * normalised, and the unit plus its extraction are persisted before the next
 * unit starts (AD-14). Any unit failure stops the whole pass — no retries,
 * no partial "best effort" continuation.
 *
 * <p>A null port, or one whose {@link LlmPort#extractsEntities()} is
 * {@code false}, makes no extraction call: every Text Unit is still split and
 * persisted, but nothing is extracted. Exact graphs built without a model go
 * through {@link ImportKnowledgeGraph} instead.
 */
public class ExtractEntitiesAndRelationships {

    private final LlmPort llmPort;
    private final GraphStorePort graphStorePort;
    private final List<String> entityTypes;
    private final FailurePolicy failurePolicy;

    public ExtractEntitiesAndRelationships(LlmPort llmPort, GraphStorePort graphStorePort) {
        this(llmPort, graphStorePort, EntityTypes.ALL);
    }

    /**
     * @param entityTypes the free-form Entity types extraction is restricted
     *                    to, in prompt order; any other type the model returns
     *                    becomes the last one (with {@link EntityTypes#ALL},
     *                    {@link EntityTypes#CONCEPT}). Null or empty means
     *                    {@link EntityTypes#ALL}.
     */
    public ExtractEntitiesAndRelationships(LlmPort llmPort, GraphStorePort graphStorePort, List<String> entityTypes) {
        this(llmPort, graphStorePort, entityTypes, FailurePolicy.FAIL_RUN);
    }

    /**
     * @param failurePolicy {@link FailurePolicy#ISOLATE_ITEM}: a unit whose
     *                      extraction fails is persisted without Entities,
     *                      recorded in the {@link ExtractionReport} and the run
     *                      continues; {@link FailurePolicy#FAIL_RUN} (the
     *                      default): the first failure stops the run
     */
    public ExtractEntitiesAndRelationships(LlmPort llmPort, GraphStorePort graphStorePort, List<String> entityTypes,
                                           FailurePolicy failurePolicy) {
        this.failurePolicy = failurePolicy == null ? FailurePolicy.FAIL_RUN : failurePolicy;
        this.entityTypes = entityTypes == null || entityTypes.isEmpty() ? EntityTypes.ALL : List.copyOf(entityTypes);
        this.llmPort = llmPort == null ? LlmPort.none() : llmPort;
        this.graphStorePort = graphStorePort;
    }

    /**
     * Extracts every Text Unit of {@code corpus} without persisting anything
     * and returns the merged (identity-deduplicated) result.
     */
    public GraphExtraction extract(Corpus corpus) {
        EntityResolver resolver = new EntityResolver();
        Map<String, Entity> entities = new LinkedHashMap<>();
        Map<String, Relationship> relationships = new LinkedHashMap<>();
        for (TextUnit unit : TextUnitSplitter.split(corpus)) {
            GraphExtraction extraction = extractUnit(unit, new ArrayList<>());
            accumulateResolved(extraction, resolver, entities, relationships, null,
                    new RetypeSink(relationships, null, null));
        }
        return new GraphExtraction(new ArrayList<>(entities.values()), new ArrayList<>(relationships.values()));
    }

    public void run(Corpus corpus) {
        run(corpus, null, null, null);
    }

    public void run(Corpus corpus, Consumer<Entity> onEntityPersisted, Consumer<Relationship> onRelationshipPersisted) {
        run(corpus, null, onEntityPersisted, onRelationshipPersisted);
    }

    /**
     * Extracts and persists the corpus unit by unit. For each unit, in order:
     * the LLM extraction, type normalisation, persistence of the unit and its
     * extraction, then {@code onTextUnitExtracted}, then one callback per
     * persisted Entity and Relationship. Callbacks only ever fire after
     * persistence, so a throwing callback cannot lose already-persisted data.
     *
     * @throws IllegalStateException if a unit's extraction fails, naming the
     *                               document and 1-based passage number;
     *                               later units are never extracted
     */
    public void run(Corpus corpus, Consumer<TextUnitProgress> onTextUnitExtracted,
                    Consumer<Entity> onEntityPersisted, Consumer<Relationship> onRelationshipPersisted) {
        run(corpus, onTextUnitExtracted, onEntityPersisted, onRelationshipPersisted, null);
    }

    public void run(Corpus corpus, Consumer<TextUnitProgress> onTextUnitExtracted,
                    Consumer<Entity> onEntityPersisted, Consumer<Relationship> onRelationshipPersisted,
                    BiConsumer<String, Entity> onEntityRetyped) {
        runWithReport(corpus, onTextUnitExtracted, onEntityPersisted, onRelationshipPersisted, onEntityRetyped);
    }

    /**
     * {@link #run(Corpus, Consumer, Consumer, Consumer, BiConsumer)} that also
     * returns what happened per unit: with {@link FailurePolicy#ISOLATE_ITEM}
     * the failed units, each still persisted as a Text Unit without Entities.
     */
    public ExtractionReport runWithReport(Corpus corpus, Consumer<TextUnitProgress> onTextUnitExtracted,
                                          Consumer<Entity> onEntityPersisted,
                                          Consumer<Relationship> onRelationshipPersisted,
                                          BiConsumer<String, Entity> onEntityRetyped) {
        List<ExtractionReport.UnitFailure> failures = new ArrayList<>();
        List<TextUnit> units = TextUnitSplitter.split(corpus);
        int total = units.size();
        EntityResolver resolver = new EntityResolver();
        Map<String, Entity> mergedEntities = new LinkedHashMap<>();
        Map<String, Relationship> mergedRelationships = new LinkedHashMap<>();
        for (int i = 0; i < total; i++) {
            TextUnit unit = units.get(i);
            GraphExtraction extraction = extractUnit(unit, failures);
            Map<String, Entity> changedEntities = new LinkedHashMap<>();
            Map<String, Relationship> changedRelationships = new LinkedHashMap<>();
            Map<String, Entity> retypedEntities = new LinkedHashMap<>();
            accumulateResolved(extraction, resolver, mergedEntities, mergedRelationships, changedEntities,
                    new RetypeSink(mergedRelationships, changedRelationships, retypedEntities));
            GraphExtraction mergedExtraction = new GraphExtraction(
                    new ArrayList<>(changedEntities.values()), new ArrayList<>(changedRelationships.values()));

            graphStorePort.persistTextUnits(corpus.id(), List.of(unit));
            for (Map.Entry<String, Entity> retyped : retypedEntities.entrySet()) {
                graphStorePort.retypeEntity(corpus.id(), retyped.getKey(), retyped.getValue());
            }
            graphStorePort.persist(corpus.id(), mergedExtraction);

            if (onTextUnitExtracted != null) {
                onTextUnitExtracted.accept(new TextUnitProgress(i + 1, total, unit.documentName(),
                        unit.id(), unit.ordinal()));
            }
            if (onEntityRetyped != null) {
                for (Map.Entry<String, Entity> retyped : retypedEntities.entrySet()) {
                    onEntityRetyped.accept(retyped.getKey(), retyped.getValue());
                }
            }
            if (onEntityPersisted != null) {
                for (Entity entity : mergedExtraction.entities()) {
                    onEntityPersisted.accept(entity);
                }
            }
            if (onRelationshipPersisted != null) {
                for (Relationship relationship : mergedExtraction.relationships()) {
                    onRelationshipPersisted.accept(relationship);
                }
            }
        }
        return new ExtractionReport(total, failures);
    }

    private static void accumulateResolved(GraphExtraction extraction, EntityResolver resolver,
                                           Map<String, Entity> mergedEntities,
                                           Map<String, Relationship> mergedRelationships,
                                           Map<String, Entity> changedEntities,
                                           RetypeSink retypeSink) {
        for (Entity entity : extraction.entities()) {
            EntityResolver.ResolvedEntity resolved = resolver.resolve(entity);
            Optional<String> previousIdentity = resolved.previousIdentity();
            if (previousIdentity.isPresent()) {
                if (changedEntities != null) {
                    changedEntities.remove(previousIdentity.get());
                }
                Entity previous = mergedEntities.remove(previousIdentity.get());
                if (previous != null) {
                    Entity rekeyedPrevious = previous.with(resolved.entity().name(), resolved.entity().type(),
                            previous.description(), previous.sourceTextUnitIds());
                    mergedEntities.merge(resolved.entity().normalizedIdentity(), rekeyedPrevious,
                            GraphElementMerger::merge);
                }
            }
            String key = resolved.entity().normalizedIdentity();
            Entity merged = mergedEntities.merge(key, resolved.entity(), GraphElementMerger::merge);
            if (changedEntities != null) {
                changedEntities.put(key, merged);
            }
            if (previousIdentity.isPresent() && retypeSink != null) {
                if (retypeSink.retypedEntities != null) {
                    retypeSink.retypedEntities.put(previousIdentity.get(), merged);
                }
                retypeSink.rekeyRelationships(previousIdentity.get(), merged);
            }
        }
        for (Relationship relationship : extraction.relationships()) {
            Relationship resolved = resolver.resolve(relationship);
            String key = relationshipKey(resolved);
            Relationship merged = mergedRelationships.merge(key, resolved, GraphElementMerger::merge);
            if (retypeSink != null && retypeSink.changedRelationships != null) {
                retypeSink.changedRelationships.put(key, merged);
            }
        }
    }

    private record RetypeSink(Map<String, Relationship> mergedRelationships,
                              Map<String, Relationship> changedRelationships,
                              Map<String, Entity> retypedEntities) {

        private void rekeyRelationships(String previousIdentity, Entity resolved) {
            Map<String, Relationship> replacements = new LinkedHashMap<>();
            List<String> replacedKeys = new ArrayList<>();
            for (Map.Entry<String, Relationship> entry : mergedRelationships.entrySet()) {
                Relationship updated = rekeyRelationship(entry.getValue(), previousIdentity, resolved);
                if (updated != entry.getValue()) {
                    replacedKeys.add(entry.getKey());
                    String updatedKey = relationshipKey(updated);
                    replacements.merge(updatedKey, updated, GraphElementMerger::merge);
                    if (changedRelationships != null) {
                        changedRelationships.merge(updatedKey, updated, GraphElementMerger::merge);
                    }
                }
            }
            for (String key : replacedKeys) {
                mergedRelationships.remove(key);
                if (changedRelationships != null) {
                    changedRelationships.remove(key);
                }
            }
            mergedRelationships.putAll(replacements);
        }
    }

    private static Relationship rekeyRelationship(Relationship relationship, String previousIdentity, Entity resolved) {
        boolean sourceMatches = Entity.identityOf(relationship.source(), relationship.sourceType()).equals(previousIdentity);
        boolean targetMatches = Entity.identityOf(relationship.target(), relationship.targetType()).equals(previousIdentity);
        if (!sourceMatches && !targetMatches) {
            return relationship;
        }
        return relationship.with(
                sourceMatches ? resolved.name() : relationship.source(),
                sourceMatches ? resolved.type() : relationship.sourceType(),
                targetMatches ? resolved.name() : relationship.target(),
                targetMatches ? resolved.type() : relationship.targetType(),
                relationship.description(), relationship.sourceTextUnitIds(), relationship.weight());
    }

    private GraphExtraction extractUnit(TextUnit unit, List<ExtractionReport.UnitFailure> failures) {
        if (!llmPort.extractsEntities()) {
            return GraphExtraction.empty();
        }
        GraphExtraction raw;
        try {
            raw = llmPort.extract(unit, entityTypes);
        } catch (RuntimeException e) {
            if (failurePolicy == FailurePolicy.ISOLATE_ITEM) {
                failures.add(new ExtractionReport.UnitFailure(unit.id(), unit.documentName(), unit.ordinal() + 1,
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                return GraphExtraction.empty();
            }
            throw new IllegalStateException("Knowledge graph extraction failed for document '"
                    + unit.documentName() + "', passage " + (unit.ordinal() + 1) + ": " + e.getMessage(), e);
        }
        return stampSourceUnit(normalizeTypes(raw, entityTypes), unit);
    }

    private static GraphExtraction normalizeTypes(GraphExtraction extraction, List<String> entityTypes) {
        if (extraction == null) {
            return new GraphExtraction(List.of(), List.of());
        }
        Map<String, Entity> entities = new LinkedHashMap<>();
        for (Entity entity : extraction.entities()) {
            if (entity == null) {
                continue;
            }
            Entity normalized = entity.with(entity.name(), EntityTypes.normalize(entity.type(), entityTypes),
                    entity.description(), entity.sourceTextUnitIds());
            entities.merge(normalized.normalizedIdentity(), normalized, GraphElementMerger::merge);
        }
        List<Relationship> relationships = new ArrayList<>();
        for (Relationship relationship : extraction.relationships()) {
            if (relationship == null) {
                continue;
            }
            relationships.add(relationship.with(
                    relationship.source(), EntityTypes.normalize(relationship.sourceType(), entityTypes),
                    relationship.target(), EntityTypes.normalize(relationship.targetType(), entityTypes),
                    relationship.description(), relationship.sourceTextUnitIds(), relationship.weight()));
        }
        return new GraphExtraction(new ArrayList<>(entities.values()), relationships);
    }

    private static GraphExtraction stampSourceUnit(GraphExtraction extraction, TextUnit unit) {
        String sourceId = unit == null ? "" : unit.id();
        List<Entity> entities = extraction.entities().stream()
                .map(entity -> entity.with(entity.name(), entity.type(),
                        GraphElementMerger.mergeDescriptions("", entity.description()), List.of(sourceId)))
                .toList();
        List<Relationship> relationships = extraction.relationships().stream()
                .map(relationship -> relationship.with(
                        relationship.source(), relationship.sourceType(),
                        relationship.target(), relationship.targetType(),
                        GraphElementMerger.mergeDescriptions("", relationship.description()),
                        List.of(sourceId), 1))
                .toList();
        return new GraphExtraction(entities, relationships);
    }

    private static String relationshipKey(Relationship relationship) {
        return Entity.identityOf(relationship.source(), relationship.sourceType())
                + "::" + relationship.type().toLowerCase(Locale.ROOT)
                + "::" + Entity.identityOf(relationship.target(), relationship.targetType());
    }
}
