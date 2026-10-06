package dev.rabauer.graphrag.core.usecase;

import java.util.List;

/**
 * What {@link UpdateSources#removeBySource(String, String)} deleted and what
 * it left stale.
 *
 * @param corpusId                 the corpus
 * @param sourceId                 the removed source (file path, document name)
 * @param removedTextUnits         Text Units deleted
 * @param removedEntities          Entity identities deleted (their own source, only
 *                                 the removed Text Units cite them, or orphaned)
 * @param orphanedEntities         of {@code removedEntities}, those deleted only
 *                                 because nothing referenced them any more
 * @param removedRelationships     Relationships deleted
 * @param updatedEntities          Entities kept but re-persisted without the
 *                                 removed Text Units
 * @param updatedRelationships     Relationships kept but re-persisted without
 *                                 the removed Text Units
 * @param removedChunks            whether the vector index's chunks of the source
 *                                 were deleted (a {@code VectorStorePort} was given)
 * @param staleCommunityIds        Communities that had a removed Entity as member;
 *                                 their summaries, hashes and embeddings are stale
 *                                 until {@link DetectCommunities#recompute(String)}
 */
public record SourceRemoval(String corpusId, String sourceId, int removedTextUnits, List<String> removedEntities,
                            List<String> orphanedEntities, int removedRelationships, int updatedEntities,
                            int updatedRelationships, boolean removedChunks, List<String> staleCommunityIds) {

    public SourceRemoval {
        removedEntities = removedEntities == null ? List.of() : List.copyOf(removedEntities);
        orphanedEntities = orphanedEntities == null ? List.of() : List.copyOf(orphanedEntities);
        staleCommunityIds = staleCommunityIds == null ? List.of() : List.copyOf(staleCommunityIds);
    }

    /** Whether Community detection should be re-run. */
    public boolean communitiesStale() {
        return !staleCommunityIds.isEmpty();
    }
}
