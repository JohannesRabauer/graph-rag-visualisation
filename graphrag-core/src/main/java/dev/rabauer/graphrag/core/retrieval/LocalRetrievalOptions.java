package dev.rabauer.graphrag.core.retrieval;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * How Local retrieval expands from its seeds.
 *
 * <p>Expansion is breadth-first: hop {@code h} takes the Relationships
 * touching the Entities reached at hop {@code h-1} (the seeds at hop 1) that
 * pass the type filters, the weight threshold and the direction, sorted by
 * {@code ordering}, and adds each with the endpoint(s) it reaches, until a cap
 * is hit. Text Units are added last, ranked by +1 per seed citing them,
 * +weight per included Relationship citing them and, with
 * {@code includeMemberTextUnits}, +1 per other included Entity citing them.
 *
 * @param seedLimit                the most seeds (at least 1)
 * @param maxHops                  how far to expand (at least 0; 0 = seeds only)
 * @param maxNodes                 the most Entities, seeds included (at least 1)
 * @param maxRelationships         the most Relationships (at least 0)
 * @param maxTextUnits             the most Text Units (at least 0)
 * @param maxItems                 the most items overall (at least 1)
 * @param includeRelationshipTypes only these Relationship types (case-insensitive); empty = all
 * @param excludeRelationshipTypes never these Relationship types (case-insensitive)
 * @param minWeight                only Relationships at least this heavy
 * @param ordering                 which Relationships of a hop come first
 * @param direction                which Relationships of an Entity are followed
 * @param includeNeighborEntities  whether reached Entities become items (with
 *                                 their locator); otherwise only seeds are
 *                                 Entity items and the walk still continues
 *                                 through them
 * @param includeMemberTextUnits   whether every included Entity's own Text
 *                                 Units (for code: their snippets) are ranked,
 *                                 not only the seeds'
 */
public record LocalRetrievalOptions(int seedLimit, int maxHops, int maxNodes, int maxRelationships, int maxTextUnits,
                                    int maxItems, Set<String> includeRelationshipTypes,
                                    Set<String> excludeRelationshipTypes, int minWeight,
                                    RelationshipOrdering ordering, Direction direction,
                                    boolean includeNeighborEntities, boolean includeMemberTextUnits) {

    public LocalRetrievalOptions {
        requireAtLeast("seedLimit", seedLimit, 1);
        requireAtLeast("maxHops", maxHops, 0);
        requireAtLeast("maxNodes", maxNodes, 1);
        requireAtLeast("maxRelationships", maxRelationships, 0);
        requireAtLeast("maxTextUnits", maxTextUnits, 0);
        requireAtLeast("maxItems", maxItems, 1);
        includeRelationshipTypes = lowerCase(includeRelationshipTypes);
        excludeRelationshipTypes = lowerCase(excludeRelationshipTypes);
        ordering = ordering == null ? RelationshipOrdering.WEIGHT_DESC : ordering;
        direction = direction == null ? Direction.BOTH : direction;
    }

    /**
     * Defaults for an agent: 3 seeds, 1 hop, at most 50 Entities, 25
     * Relationships (heaviest first, both directions), 10 Text Units and 100
     * items; reached Entities and their own Text Units included.
     */
    public static LocalRetrievalOptions defaults() {
        return new LocalRetrievalOptions(3, 1, 50, 25, 10, 100, Set.of(), Set.of(), 1,
                RelationshipOrdering.WEIGHT_DESC, Direction.BOTH, true, true);
    }

    /**
     * Exactly the context Local Search answers from (Story 15.2): 1 hop, the 10
     * heaviest Relationships touching a seed, the 5 best-ranked Text Units,
     * only seeds as Entity items, no node or item cap.
     */
    public static LocalRetrievalOptions answerContext() {
        return new LocalRetrievalOptions(3, 1, Integer.MAX_VALUE, 10, 5, Integer.MAX_VALUE, Set.of(), Set.of(), 1,
                RelationshipOrdering.WEIGHT_DESC, Direction.BOTH, false, false);
    }

    /** Whether a Relationship of {@code type} and {@code weight} passes the filters. */
    public boolean accepts(String type, int weight) {
        String lower = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return weight >= minWeight
                && (includeRelationshipTypes.isEmpty() || includeRelationshipTypes.contains(lower))
                && !excludeRelationshipTypes.contains(lower);
    }

    public LocalRetrievalOptions withSeedLimit(int value) {
        return new LocalRetrievalOptions(value, maxHops, maxNodes, maxRelationships, maxTextUnits, maxItems,
                includeRelationshipTypes, excludeRelationshipTypes, minWeight, ordering, direction,
                includeNeighborEntities, includeMemberTextUnits);
    }

    public LocalRetrievalOptions withMaxHops(int value) {
        return new LocalRetrievalOptions(seedLimit, value, maxNodes, maxRelationships, maxTextUnits, maxItems,
                includeRelationshipTypes, excludeRelationshipTypes, minWeight, ordering, direction,
                includeNeighborEntities, includeMemberTextUnits);
    }

    public LocalRetrievalOptions withMaxNodes(int value) {
        return new LocalRetrievalOptions(seedLimit, maxHops, value, maxRelationships, maxTextUnits, maxItems,
                includeRelationshipTypes, excludeRelationshipTypes, minWeight, ordering, direction,
                includeNeighborEntities, includeMemberTextUnits);
    }

    public LocalRetrievalOptions withMaxRelationships(int value) {
        return new LocalRetrievalOptions(seedLimit, maxHops, maxNodes, value, maxTextUnits, maxItems,
                includeRelationshipTypes, excludeRelationshipTypes, minWeight, ordering, direction,
                includeNeighborEntities, includeMemberTextUnits);
    }

    public LocalRetrievalOptions withMaxTextUnits(int value) {
        return new LocalRetrievalOptions(seedLimit, maxHops, maxNodes, maxRelationships, value, maxItems,
                includeRelationshipTypes, excludeRelationshipTypes, minWeight, ordering, direction,
                includeNeighborEntities, includeMemberTextUnits);
    }

    public LocalRetrievalOptions withMaxItems(int value) {
        return new LocalRetrievalOptions(seedLimit, maxHops, maxNodes, maxRelationships, maxTextUnits, value,
                includeRelationshipTypes, excludeRelationshipTypes, minWeight, ordering, direction,
                includeNeighborEntities, includeMemberTextUnits);
    }

    public LocalRetrievalOptions withIncludeRelationshipTypes(Set<String> value) {
        return new LocalRetrievalOptions(seedLimit, maxHops, maxNodes, maxRelationships, maxTextUnits, maxItems,
                value, excludeRelationshipTypes, minWeight, ordering, direction,
                includeNeighborEntities, includeMemberTextUnits);
    }

    public LocalRetrievalOptions withExcludeRelationshipTypes(Set<String> value) {
        return new LocalRetrievalOptions(seedLimit, maxHops, maxNodes, maxRelationships, maxTextUnits, maxItems,
                includeRelationshipTypes, value, minWeight, ordering, direction,
                includeNeighborEntities, includeMemberTextUnits);
    }

    public LocalRetrievalOptions withMinWeight(int value) {
        return new LocalRetrievalOptions(seedLimit, maxHops, maxNodes, maxRelationships, maxTextUnits, maxItems,
                includeRelationshipTypes, excludeRelationshipTypes, value, ordering, direction,
                includeNeighborEntities, includeMemberTextUnits);
    }

    public LocalRetrievalOptions withOrdering(RelationshipOrdering value) {
        return new LocalRetrievalOptions(seedLimit, maxHops, maxNodes, maxRelationships, maxTextUnits, maxItems,
                includeRelationshipTypes, excludeRelationshipTypes, minWeight, value, direction,
                includeNeighborEntities, includeMemberTextUnits);
    }

    public LocalRetrievalOptions withDirection(Direction value) {
        return new LocalRetrievalOptions(seedLimit, maxHops, maxNodes, maxRelationships, maxTextUnits, maxItems,
                includeRelationshipTypes, excludeRelationshipTypes, minWeight, ordering, value,
                includeNeighborEntities, includeMemberTextUnits);
    }

    public LocalRetrievalOptions withIncludeNeighborEntities(boolean value) {
        return new LocalRetrievalOptions(seedLimit, maxHops, maxNodes, maxRelationships, maxTextUnits, maxItems,
                includeRelationshipTypes, excludeRelationshipTypes, minWeight, ordering, direction,
                value, includeMemberTextUnits);
    }

    public LocalRetrievalOptions withIncludeMemberTextUnits(boolean value) {
        return new LocalRetrievalOptions(seedLimit, maxHops, maxNodes, maxRelationships, maxTextUnits, maxItems,
                includeRelationshipTypes, excludeRelationshipTypes, minWeight, ordering, direction,
                includeNeighborEntities, value);
    }

    private static void requireAtLeast(String name, int value, int minimum) {
        if (value < minimum) {
            throw new IllegalArgumentException(name + " must be at least " + minimum + ", was " + value);
        }
    }

    private static Set<String> lowerCase(Set<String> types) {
        if (types == null || types.isEmpty()) {
            return Set.of();
        }
        return types.stream().filter(type -> type != null && !type.isBlank())
                .map(type -> type.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Which Relationships of a hop are added first. */
    public enum RelationshipOrdering {
        /** Heaviest first; equal weights keep stored order. */
        WEIGHT_DESC,
        /** Lightest first; equal weights keep stored order. */
        WEIGHT_ASC,
        /** Stored order. */
        STORED
    }

    /** Which Relationships of a reached Entity are followed. */
    public enum Direction {
        /** Relationships where it is the source or the target. */
        BOTH,
        /** Relationships where it is the source (for code: what it calls, extends, uses). */
        OUTGOING,
        /** Relationships where it is the target (for code: its callers, implementors, users). */
        INCOMING
    }
}
