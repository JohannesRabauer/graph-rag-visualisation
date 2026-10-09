package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Citation;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.port.GraphReadPort;
import dev.rabauer.graphrag.core.retrieval.LocalRetrievalOptions;
import dev.rabauer.graphrag.core.retrieval.RetrievedItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The breadth-first Local expansion shared by Local Search's answer context
 * ({@link LocalContextAssembler}, with {@link LocalRetrievalOptions#answerContext()})
 * and the retrieval-only use cases ({@link RetrieveLocalContext},
 * {@link RetrieveDriftContext}). See {@link LocalRetrievalOptions} for the
 * rules. Every touched element is recorded once, in touch order, as a
 * {@link Touch} that knows its trace step, its synthesis-context item and its
 * retrieval item.
 */
final class LocalExpansion {

    private final GraphReadPort graph;

    LocalExpansion(GraphReadPort graph) {
        this.graph = graph;
    }

    /** One touched element: the trace step, the un-numbered context item, the retrieval details. */
    record Touch(RetrievalStep step, LocalContextAssembler.Item item, String text, double score, int hop,
                 Citation citation) {

        RetrievedItem toRetrievedItem(int number) {
            return new RetrievedItem(number, step.kind(), step.identifier(), step.label(), text, step.locator(),
                    step.attributes(), score, hop);
        }
    }

    /**
     * Expands from {@code seeds} (with their scores, by identity) and
     * returns the touches in order: seeds, then per hop Relationships and the
     * Entities they reach, then Text Units.
     */
    List<Touch> expand(List<Entity> seeds, Map<String, Double> seedScores, String corpusId,
                       LocalRetrievalOptions options) {
        List<Touch> touches = new ArrayList<>();
        Map<String, Entity> included = new LinkedHashMap<>();
        for (Entity seed : seeds) {
            if (included.size() >= options.maxNodes() || touches.size() >= options.maxItems()) {
                break;
            }
            if (included.putIfAbsent(seed.normalizedIdentity(), seed) == null) {
                touches.add(entityTouch(seed, seedScores.getOrDefault(seed.normalizedIdentity(), 0.0), 0));
            }
        }

        Set<String> includedEdges = new LinkedHashSet<>();
        List<Relationship> includedRelationships = new ArrayList<>();
        Set<String> frontier = new LinkedHashSet<>(included.keySet());
        boolean full = false;
        for (int hop = 1; hop <= options.maxHops() && !frontier.isEmpty() && !full; hop++) {
            List<Relationship> candidates = new ArrayList<>();
            for (Relationship relationship : nonNull(graph.relationshipsTouching(corpusId, frontier))) {
                if (follows(relationship, frontier, options) && options.accepts(relationship.type(), relationship.weight())
                        && !includedEdges.contains(edgeKey(relationship))) {
                    candidates.add(relationship);
                }
            }
            sort(candidates, options, included.keySet());
            Map<String, Entity> entityByIdentity = null;

            Set<String> next = new LinkedHashSet<>();
            int hopRelationships = 0;
            int hopNewNodes = 0;
            for (Relationship relationship : candidates) {
                if (includedRelationships.size() >= options.maxRelationships()
                        || touches.size() >= options.maxItems()) {
                    full = true;
                    break;
                }
                if (hopRelationships >= options.maxRelationshipsPerHop()) {
                    break;
                }
                int fresh = newEndpoints(relationship, included.keySet());
                if (fresh > options.maxNewNodesPerHop() - hopNewNodes) {
                    continue;
                }
                if (!includedEdges.add(edgeKey(relationship))) {
                    continue;
                }
                hopRelationships++;
                includedRelationships.add(relationship);
                touches.add(relationshipTouch(relationship, hop));
                for (String endpoint : List.of(relationship.sourceIdentity(), relationship.targetIdentity())) {
                    if (included.containsKey(endpoint) || included.size() >= options.maxNodes()) {
                        continue;
                    }
                    if (entityByIdentity == null) {
                        entityByIdentity = endpointEntities(corpusId, candidates, included.keySet());
                    }
                    Entity reached = entityByIdentity.get(endpoint);
                    if (reached == null) {
                        reached = endpoint.equals(relationship.sourceIdentity())
                                ? new Entity(relationship.source(), relationship.sourceType())
                                : new Entity(relationship.target(), relationship.targetType());
                    }
                    included.put(endpoint, reached);
                    next.add(endpoint);
                    hopNewNodes++;
                    if (options.includeNeighborEntities() && touches.size() < options.maxItems()) {
                        touches.add(entityTouch(reached, 0, hop));
                    }
                }
            }
            frontier = next;
        }

        // Rank Text Units: +1 per seed, (+1 per other included Entity), +weight per included Relationship.
        Map<String, Integer> scoreByUnit = new LinkedHashMap<>();
        Set<String> seedIdentities = new LinkedHashSet<>();
        for (Entity seed : seeds) {
            seedIdentities.add(seed.normalizedIdentity());
            for (String unitId : seed.sourceTextUnitIds()) {
                scoreByUnit.merge(unitId, 1, Integer::sum);
            }
        }
        if (options.includeMemberTextUnits()) {
            for (Entity entity : included.values()) {
                if (!seedIdentities.contains(entity.normalizedIdentity())) {
                    for (String unitId : entity.sourceTextUnitIds()) {
                        scoreByUnit.merge(unitId, 1, Integer::sum);
                    }
                }
            }
        }
        for (Relationship relationship : includedRelationships) {
            for (String unitId : relationship.sourceTextUnitIds()) {
                scoreByUnit.merge(unitId, relationship.weight(), Integer::sum);
            }
        }
        List<String> rankedUnits = new ArrayList<>(scoreByUnit.keySet());
        // List.sort is stable, so ties keep first-seen order.
        rankedUnits.sort(Comparator.comparingInt((String unitId) -> scoreByUnit.get(unitId)).reversed());
        int units = 0;
        for (String unitId : rankedUnits) {
            if (units >= options.maxTextUnits() || touches.size() >= options.maxItems()) {
                break;
            }
            Touch touch = textUnitTouch(graph, corpusId, unitId, scoreByUnit.get(unitId));
            if (touch != null) {
                touches.add(touch);
                units++;
            }
        }
        return touches;
    }

    private static boolean follows(Relationship relationship, Set<String> frontier, LocalRetrievalOptions options) {
        boolean fromSource = frontier.contains(relationship.sourceIdentity());
        boolean fromTarget = frontier.contains(relationship.targetIdentity());
        return switch (options.direction()) {
            case BOTH -> fromSource || fromTarget;
            case OUTGOING -> fromSource;
            case INCOMING -> fromTarget;
        };
    }

    /** How many distinct endpoints of {@code relationship} are not included yet. */
    private static int newEndpoints(Relationship relationship, Set<String> included) {
        boolean source = !included.contains(relationship.sourceIdentity());
        boolean target = !included.contains(relationship.targetIdentity())
                && !relationship.targetIdentity().equals(relationship.sourceIdentity());
        return (source ? 1 : 0) + (target ? 1 : 0);
    }

    /** The hop's candidates, best first: by the caller's comparator, else by the ordering; ties keep stored order. */
    private static void sort(List<Relationship> relationships, LocalRetrievalOptions options, Set<String> included) {
        // List.sort is stable, so equal scores keep their stored order.
        if (options.relationshipComparator() != null) {
            relationships.sort(options.relationshipComparator());
            return;
        }
        switch (options.ordering()) {
            case WEIGHT_DESC -> relationships.sort(Comparator.comparingInt(Relationship::weight).reversed());
            case WEIGHT_ASC -> relationships.sort(Comparator.comparingInt(Relationship::weight));
            case NEW_NODES_FIRST -> relationships.sort(Comparator
                    .comparingInt((Relationship relationship) -> newEndpoints(relationship, included) > 0 ? 0 : 1)
                    .thenComparing(Comparator.comparingInt(Relationship::weight).reversed()));
            case STORED -> {
                // Stored order.
            }
        }
    }

    private static <T> List<T> nonNull(Collection<T> values) {
        return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull).toList();
    }

    /** One lookup per hop: the Entities at the not-yet-included endpoints of {@code candidates}. */
    private Map<String, Entity> endpointEntities(String corpusId, List<Relationship> candidates, Set<String> included) {
        Set<String> wanted = new LinkedHashSet<>();
        for (Relationship relationship : candidates) {
            for (String endpoint : List.of(relationship.sourceIdentity(), relationship.targetIdentity())) {
                if (!included.contains(endpoint)) {
                    wanted.add(endpoint);
                }
            }
        }
        Map<String, Entity> byIdentity = new HashMap<>();
        for (Entity entity : nonNull(graph.entities(corpusId, wanted))) {
            byIdentity.putIfAbsent(entity.normalizedIdentity(), entity);
        }
        return byIdentity;
    }

    static String edgeKey(Relationship relationship) {
        return relationship.sourceIdentity() + "->" + relationship.type() + "->" + relationship.targetIdentity();
    }

    static Touch entityTouch(Entity entity, double score, int hop) {
        RetrievalStep step = LocalContextAssembler.entityStep(entity);
        String text = LocalContextAssembler.entityText(entity);
        return new Touch(step, new LocalContextAssembler.Item("ENTITY:" + entity.normalizedIdentity(),
                RetrievalStep.Kind.ENTITY, text, null), text, score, hop, null);
    }

    static Touch relationshipTouch(Relationship relationship, int hop) {
        RetrievalStep step = LocalContextAssembler.relationshipStep(relationship);
        String text = LocalContextAssembler.relationshipText(relationship);
        return new Touch(step, new LocalContextAssembler.Item("RELATIONSHIP:" + step.identifier(),
                RetrievalStep.Kind.RELATIONSHIP, text, null), text, relationship.weight(), hop, null);
    }

    /** The Text Unit's touch, or null when it is missing or has no text (a store failure propagates). */
    static Touch textUnitTouch(GraphReadPort graph, String corpusId, String unitId, double score) {
        Optional<TextUnit> loaded = LocalContextAssembler.loadTextUnit(graph, corpusId, unitId);
        if (loaded.isEmpty() || loaded.get().text() == null) {
            return null;
        }
        TextUnit unit = loaded.get();
        String excerpt = LocalContextAssembler.excerpt(unit.text());
        RetrievalStep step = new RetrievalStep(RetrievalStep.Kind.TEXT_UNIT, unitId, excerpt, unit.locator(),
                unit.attributes());
        Citation citation = new Citation(unitId, unit.documentName(), excerpt, unit.locator(), unit.attributes());
        return new Touch(step, new LocalContextAssembler.Item("TEXT_UNIT:" + unitId, RetrievalStep.Kind.TEXT_UNIT,
                unit.text(), unitId), unit.text(), score, -1, citation);
    }
}
