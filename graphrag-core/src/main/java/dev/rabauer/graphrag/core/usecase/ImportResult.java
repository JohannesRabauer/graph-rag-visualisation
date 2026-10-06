package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Community;

import java.util.List;

/**
 * What {@link ImportKnowledgeGraph} persisted for one corpus.
 *
 * @param corpusId              the corpus the graph was imported into
 * @param textUnits             Text Units persisted
 * @param entities              Entities persisted after merging repeated identities
 *                              (placeholders for missing endpoints included)
 * @param relationships         Relationships persisted after merging repeated keys
 * @param placeholderEntities   Entities created for Relationship endpoints that
 *                              neither the import nor the store contained
 * @param droppedRelationships  Relationships dropped because an endpoint was
 *                              missing ({@link ImportKnowledgeGraph.MissingEndpoints#DROP})
 * @param communitySource       where the Communities came from
 * @param communities           the Communities persisted (supplied or detected);
 *                              empty when none were supplied or detected
 * @param embedded              whether Entities and Communities were embedded
 */
public record ImportResult(String corpusId, int textUnits, int entities, int relationships,
                           int placeholderEntities, int droppedRelationships,
                           CommunitySource communitySource, List<Community> communities, boolean embedded) {

    public ImportResult {
        corpusId = corpusId == null ? "" : corpusId;
        communitySource = communitySource == null ? CommunitySource.NONE : communitySource;
        communities = communities == null ? List.of() : List.copyOf(communities);
    }

    /** Where an import's Communities came from. */
    public enum CommunitySource {
        /** The caller supplied them; detection was skipped. */
        SUPPLIED,
        /** {@link DetectCommunities} detected them. */
        DETECTED,
        /** Neither: detection was switched off, or nothing reached the minimum size. */
        NONE
    }
}
