package dev.rabauer.graphrag.core.retrieval;

/**
 * How Global retrieval picks Communities and what it takes from each.
 *
 * <p>Candidates: with a semantic embedding model, the Communities most
 * similar to the question; otherwise each Community scores its
 * keyword overlap with {@code title + summary} plus, with
 * {@code matchMembers}, the seed scores of its members that the
 * {@link SeedMatcher} finds (so an identifier in the question finds the
 * Community containing that class). The two parts are on different scales
 * (integer keyword counts, a matcher's own scores), so each is divided by the
 * best Community's value first: a Community scores between 0 and 2, whatever
 * the matcher (identifier, fused, keyword). Communities scoring above zero are
 * kept, highest first, id as tiebreak.
 *
 * @param maxCommunities             the most Communities (at least 1)
 * @param memberEntitiesPerCommunity the most member Entities per Community
 *                                   (matched members first, then the best
 *                                   connected); 0 = none
 * @param textUnitsPerCommunity      the most member Text Units per Community,
 *                                   never repeating one already added
 * @param matchMembers               whether member seed matches add to a
 *                                   Community's score
 * @param memberSeedLimit            how many seeds the matcher is asked for
 *                                   when scoring members (at least 1)
 */
public record GlobalRetrievalOptions(int maxCommunities, int memberEntitiesPerCommunity, int textUnitsPerCommunity,
                                     boolean matchMembers, int memberSeedLimit) {

    public GlobalRetrievalOptions {
        if (maxCommunities < 1 || memberSeedLimit < 1) {
            throw new IllegalArgumentException("maxCommunities and memberSeedLimit must be at least 1");
        }
        if (memberEntitiesPerCommunity < 0 || textUnitsPerCommunity < 0) {
            throw new IllegalArgumentException("per-Community limits must not be negative");
        }
    }

    /** 3 Communities, each with up to 5 member Entities and 2 Text Units; members count towards the score. */
    public static GlobalRetrievalOptions defaults() {
        return new GlobalRetrievalOptions(3, 5, 2, true, 20);
    }

    /** The context Global Search answers from (Story 15.3): 3 Communities, 2 Text Units each, no members. */
    public static GlobalRetrievalOptions answerContext() {
        return new GlobalRetrievalOptions(3, 0, 2, false, 20);
    }

    public GlobalRetrievalOptions withMaxCommunities(int value) {
        return new GlobalRetrievalOptions(value, memberEntitiesPerCommunity, textUnitsPerCommunity, matchMembers,
                memberSeedLimit);
    }

    public GlobalRetrievalOptions withMemberEntitiesPerCommunity(int value) {
        return new GlobalRetrievalOptions(maxCommunities, value, textUnitsPerCommunity, matchMembers,
                memberSeedLimit);
    }

    public GlobalRetrievalOptions withTextUnitsPerCommunity(int value) {
        return new GlobalRetrievalOptions(maxCommunities, memberEntitiesPerCommunity, value, matchMembers,
                memberSeedLimit);
    }

    public GlobalRetrievalOptions withMatchMembers(boolean value) {
        return new GlobalRetrievalOptions(maxCommunities, memberEntitiesPerCommunity, textUnitsPerCommunity, value,
                memberSeedLimit);
    }

    public GlobalRetrievalOptions withMemberSeedLimit(int value) {
        return new GlobalRetrievalOptions(maxCommunities, memberEntitiesPerCommunity, textUnitsPerCommunity,
                matchMembers, value);
    }
}
