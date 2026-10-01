package io.graphrag.core.usecase;

import io.graphrag.core.domain.Corpus;
import io.graphrag.core.domain.Community;
import io.graphrag.core.domain.CommunityMembership;
import io.graphrag.core.domain.CommunitySummary;
import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.port.GraphStorePort;
import io.graphrag.core.port.LlmPort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Detects Communities in a corpus's persisted knowledge graph, summarizes and
 * persists them, and reports each one to an optional callback.
 *
 * <p>The grouping itself comes from {@link GraphStorePort#detectCommunities(String)}:
 * connected components by default, or a modularity-based algorithm such as GDS
 * Leiden when the graph store provides one. Whatever order the port returns,
 * Community ids are always {@code community-1..n}, assigned in the order of each
 * group's first member in {@link GraphStorePort#entities(String)}, and members
 * keep that entity order, so ids are deterministic for any adapter. An Entity
 * the port leaves out of every group becomes its own single-member Community.</p>
 */
public class DetectCommunities {

    /** At most this many members (in entity order) are handed to the LLM per Community. */
    static final int MAX_SUMMARY_MEMBERS = 25;
    /** At most this many internal Relationships (highest weight first) are handed to the LLM per Community. */
    static final int MAX_SUMMARY_RELATIONSHIPS = 30;

    private final GraphStorePort graphStorePort;
    private final LlmPort llmPort;

    public DetectCommunities(GraphStorePort graphStorePort) {
        this(graphStorePort, null);
    }

    public DetectCommunities(GraphStorePort graphStorePort, LlmPort llmPort) {
        this.graphStorePort = graphStorePort;
        this.llmPort = llmPort;
    }

    public List<Community> detect(Corpus corpus) {
        return detect(corpus, null);
    }

    /**
     * Detects Communities and persists them, then reports each detected Community
     * (with its member Entity identities) to the optional callback, one invocation
     * per Community, invoked only after both persistCommunities() and
     * persistCommunityMemberships() have run — a callback that throws can no longer
     * lose already-detected Communities.
     */
    public List<Community> detect(Corpus corpus, BiConsumer<Community, List<String>> onCommunityDetected) {
        Collection<Entity> stored = graphStorePort.entities(corpus.id());
        if (stored == null || stored.isEmpty()) {
            return List.of();
        }
        List<Entity> entities = stored.stream().filter(Objects::nonNull).toList();
        if (entities.isEmpty()) {
            return List.of();
        }

        List<List<Entity>> groups = orderedGroups(entities, graphStorePort.detectCommunities(corpus.id()));

        List<Community> communities = new ArrayList<>();
        List<CommunityMembership> memberships = new ArrayList<>();
        List<Relationship> allRelationships = storedRelationships(corpus.id());
        int index = 1;
        for (List<Entity> members : groups) {
            String communityId = "community-" + index++;
            CommunitySummary generated = summarizeCommunity(members, allRelationships);
            communities.add(new Community(communityId, generated.title(), generated.summary()));
            for (Entity member : members) {
                memberships.add(new CommunityMembership(communityId, member.normalizedIdentity()));
            }
        }

        graphStorePort.persistCommunities(corpus.id(), communities);
        graphStorePort.persistCommunityMemberships(corpus.id(), memberships);

        if (onCommunityDetected != null) {
            Map<String, List<String>> memberIdentitiesByCommunityId = new LinkedHashMap<>();
            for (CommunityMembership membership : memberships) {
                memberIdentitiesByCommunityId
                        .computeIfAbsent(membership.communityId(), ignored -> new ArrayList<>())
                        .add(membership.entityIdentity());
            }
            for (Community community : communities) {
                onCommunityDetected.accept(community,
                        memberIdentitiesByCommunityId.getOrDefault(community.id(), List.of()));
            }
        }

        return communities;
    }

    public void run(Corpus corpus) {
        detect(corpus);
    }

    public void run(Corpus corpus, BiConsumer<Community, List<String>> onCommunityDetected) {
        detect(corpus, onCommunityDetected);
    }

    private List<Relationship> storedRelationships(String corpusId) {
        Collection<Relationship> stored = graphStorePort.relationships(corpusId);
        if (stored == null) {
            return List.of();
        }
        return stored.stream().filter(Objects::nonNull).toList();
    }

    /**
     * Summarizes one Community from its first {@value #MAX_SUMMARY_MEMBERS} members
     * and its internal Relationships (both endpoints among those passed members), the
     * {@value #MAX_SUMMARY_RELATIONSHIPS} highest-weight first, ties in stored order.
     * A port returning null gets the deterministic title and summary (no second call).
     */
    private CommunitySummary summarizeCommunity(List<Entity> members, List<Relationship> allRelationships) {
        List<Entity> cappedMembers = members.stream().limit(MAX_SUMMARY_MEMBERS).toList();
        CommunitySummary generated = llmPort == null ? null
                : llmPort.summarizeCommunity(cappedMembers, internalRelationships(cappedMembers, allRelationships));
        if (generated == null) {
            return new CommunitySummary(CommunitySummary.deterministicTitle(cappedMembers),
                    fallbackSummary(cappedMembers));
        }
        return generated;
    }

    static List<Relationship> internalRelationships(List<Entity> members, List<Relationship> relationships) {
        Set<String> memberIdentities = new HashSet<>();
        for (Entity member : members) {
            memberIdentities.add(member.normalizedIdentity());
        }
        // List.sort is stable, so ties keep stored order.
        List<Relationship> internal = new ArrayList<>();
        for (Relationship relationship : relationships) {
            if (memberIdentities.contains(Entity.identityOf(relationship.source(), relationship.sourceType()))
                    && memberIdentities.contains(Entity.identityOf(relationship.target(), relationship.targetType()))) {
                internal.add(relationship);
            }
        }
        internal.sort(Comparator.comparingInt(Relationship::weight).reversed());
        return internal.size() > MAX_SUMMARY_RELATIONSHIPS
                ? List.copyOf(internal.subList(0, MAX_SUMMARY_RELATIONSHIPS))
                : List.copyOf(internal);
    }

    private static String fallbackSummary(List<Entity> members) {
        if (members == null || members.isEmpty()) {
            return "A small connected cluster of related entities.";
        }
        String names = members.stream()
                .map(Entity::name)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .limit(4)
                .reduce((left, right) -> left + ", " + right)
                .orElse("related entities");
        return "This community centers on " + names + ".";
    }

    /**
     * Maps the port's identity groups back to the Entities carrying those
     * identities (duplicates kept), in entity order, and orders the groups by
     * the entity index of their first member. An identity claimed by several
     * groups stays with the first; Entities no group mentions become
     * singletons; groups without any known Entity are dropped.
     */
    private static List<List<Entity>> orderedGroups(List<Entity> entities, List<List<String>> identityGroups) {
        Map<String, Integer> groupByIdentity = new HashMap<>();
        if (identityGroups != null) {
            int groupIndex = 0;
            for (List<String> group : identityGroups) {
                if (group != null) {
                    for (String identity : group) {
                        if (identity != null) {
                            groupByIdentity.putIfAbsent(identity, groupIndex);
                        }
                    }
                }
                groupIndex++;
            }
        }

        // Insertion order of this map is the entity index of each group's first member.
        Map<Object, List<Entity>> membersByGroup = new LinkedHashMap<>();
        for (Entity entity : entities) {
            String identity = entity.normalizedIdentity();
            Object key = groupByIdentity.containsKey(identity)
                    ? groupByIdentity.get(identity)
                    : "singleton::" + identity;
            List<Entity> members = membersByGroup.computeIfAbsent(key, ignored -> new ArrayList<>());
            // Same-identity Entities with differing details are kept; exact repeats are not (as before).
            if (!members.contains(entity)) {
                members.add(entity);
            }
        }
        return membersByGroup.values().stream().map(List::copyOf).toList();
    }
}
