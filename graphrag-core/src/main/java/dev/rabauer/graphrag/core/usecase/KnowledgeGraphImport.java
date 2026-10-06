package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.TextUnit;

import java.util.List;
import java.util.Objects;

/**
 * A pre-built knowledge graph handed to {@link ImportKnowledgeGraph}: exact
 * Text Units, Entities and Relationships produced without a model (for
 * example from a bytecode scan), and optionally the caller's own Communities
 * and memberships (for example one Community per package or module).
 *
 * <p>Null lists become empty and null elements are dropped. Communities and
 * memberships are optional: when both are empty, the importer detects
 * Communities itself (unless told not to).
 *
 * @param textUnits     the source passages (code snippets, documents) the
 *                      Entities and Relationships cite via
 *                      {@code sourceTextUnitIds}
 * @param entities      the graph's nodes; repeated identities are merged
 * @param relationships the graph's edges; repeated
 *                      {@code (source, type, target)} keys are merged and
 *                      their weights summed
 * @param communities   caller-supplied Communities; may be empty
 * @param memberships   caller-supplied memberships; a membership whose
 *                      Community is not in {@code communities} gets a
 *                      Community with the deterministic title and summary
 */
public record KnowledgeGraphImport(List<TextUnit> textUnits, List<Entity> entities, List<Relationship> relationships,
                                   List<Community> communities, List<CommunityMembership> memberships) {

    public KnowledgeGraphImport {
        textUnits = withoutNulls(textUnits);
        entities = withoutNulls(entities);
        relationships = withoutNulls(relationships);
        communities = withoutNulls(communities);
        memberships = withoutNulls(memberships);
    }

    /** A graph without caller-supplied Communities. */
    public static KnowledgeGraphImport of(List<TextUnit> textUnits, List<Entity> entities,
                                          List<Relationship> relationships) {
        return new KnowledgeGraphImport(textUnits, entities, relationships, List.of(), List.of());
    }

    /** This graph with caller-supplied Communities and memberships. */
    public KnowledgeGraphImport withCommunities(List<Community> communities, List<CommunityMembership> memberships) {
        return new KnowledgeGraphImport(textUnits, entities, relationships, communities, memberships);
    }

    /** Whether the caller supplied any Community or membership. */
    public boolean hasSuppliedCommunities() {
        return !communities.isEmpty() || !memberships.isEmpty();
    }

    private static <T> List<T> withoutNulls(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }
}
