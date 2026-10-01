package io.graphrag.core.usecase;

import io.graphrag.core.domain.Corpus;
import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.GraphExtraction;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.TextUnit;
import io.graphrag.core.port.GraphStorePort;
import io.graphrag.core.port.LlmPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Executes the knowledge-graph extraction pass for a corpus, one Text Unit
 * at a time (AD-24): the corpus is split by {@link TextUnitSplitter}, each
 * unit is extracted against {@link EntityTypes#ALL}, its types are
 * normalised, and the unit plus its extraction are persisted before the next
 * unit starts (AD-14). Any unit failure stops the whole pass — no retries,
 * no partial "best effort" continuation.
 */
public class ExtractEntitiesAndRelationships {

    private final LlmPort llmPort;
    private final GraphStorePort graphStorePort;

    public ExtractEntitiesAndRelationships(LlmPort llmPort, GraphStorePort graphStorePort) {
        this.llmPort = llmPort;
        this.graphStorePort = graphStorePort;
    }

    /**
     * Extracts every Text Unit of {@code corpus} without persisting anything
     * and returns the merged (identity-deduplicated) result.
     */
    public GraphExtraction extract(Corpus corpus) {
        Map<String, Entity> entities = new LinkedHashMap<>();
        Map<String, Relationship> relationships = new LinkedHashMap<>();
        for (TextUnit unit : TextUnitSplitter.split(corpus)) {
            GraphExtraction extraction = extractUnit(unit);
            for (Entity entity : extraction.entities()) {
                entities.merge(entity.normalizedIdentity(), entity, GraphElementMerger::merge);
            }
            for (Relationship relationship : extraction.relationships()) {
                relationships.merge(relationshipKey(relationship), relationship, GraphElementMerger::merge);
            }
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
        List<TextUnit> units = TextUnitSplitter.split(corpus);
        int total = units.size();
        Map<String, Entity> mergedEntities = new LinkedHashMap<>();
        Map<String, Relationship> mergedRelationships = new LinkedHashMap<>();
        for (int i = 0; i < total; i++) {
            TextUnit unit = units.get(i);
            GraphExtraction extraction = extractUnit(unit);
            List<Entity> changedEntities = new ArrayList<>();
            List<Relationship> changedRelationships = new ArrayList<>();
            for (Entity entity : extraction.entities()) {
                Entity merged = mergedEntities.merge(entity.normalizedIdentity(), entity, GraphElementMerger::merge);
                changedEntities.add(merged);
            }
            for (Relationship relationship : extraction.relationships()) {
                Relationship merged = mergedRelationships.merge(relationshipKey(relationship), relationship,
                        GraphElementMerger::merge);
                changedRelationships.add(merged);
            }
            GraphExtraction mergedExtraction = new GraphExtraction(changedEntities, changedRelationships);

            graphStorePort.persistTextUnits(corpus.id(), List.of(unit));
            graphStorePort.persist(corpus.id(), mergedExtraction);

            if (onTextUnitExtracted != null) {
                onTextUnitExtracted.accept(new TextUnitProgress(i + 1, total, unit.documentName()));
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
    }

    private GraphExtraction extractUnit(TextUnit unit) {
        GraphExtraction raw;
        try {
            raw = llmPort.extract(unit, EntityTypes.ALL);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Knowledge graph extraction failed for document '"
                    + unit.documentName() + "', passage " + (unit.ordinal() + 1) + ": " + e.getMessage(), e);
        }
        return stampSourceUnit(normalizeTypes(raw), unit);
    }

    private static GraphExtraction normalizeTypes(GraphExtraction extraction) {
        if (extraction == null) {
            return new GraphExtraction(List.of(), List.of());
        }
        Map<String, Entity> entities = new LinkedHashMap<>();
        for (Entity entity : extraction.entities()) {
            if (entity == null) {
                continue;
            }
            Entity normalized = new Entity(entity.name(), EntityTypes.normalize(entity.type()),
                    entity.description(), entity.sourceTextUnitIds());
            entities.merge(normalized.normalizedIdentity(), normalized, GraphElementMerger::merge);
        }
        List<Relationship> relationships = new ArrayList<>();
        for (Relationship relationship : extraction.relationships()) {
            if (relationship == null) {
                continue;
            }
            relationships.add(new Relationship(
                    relationship.source(), EntityTypes.normalize(relationship.sourceType()),
                    relationship.type(),
                    relationship.target(), EntityTypes.normalize(relationship.targetType()),
                    relationship.description(), relationship.sourceTextUnitIds(), relationship.weight()));
        }
        return new GraphExtraction(new ArrayList<>(entities.values()), relationships);
    }

    private static GraphExtraction stampSourceUnit(GraphExtraction extraction, TextUnit unit) {
        String sourceId = unit == null ? "" : unit.id();
        List<Entity> entities = extraction.entities().stream()
                .map(entity -> new Entity(entity.name(), entity.type(), entity.description(), List.of(sourceId)))
                .toList();
        List<Relationship> relationships = extraction.relationships().stream()
                .map(relationship -> new Relationship(
                        relationship.source(), relationship.sourceType(), relationship.type(),
                        relationship.target(), relationship.targetType(), relationship.description(),
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
