package com.graphraglens.core.usecase;

import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.Community;
import com.graphraglens.core.domain.CommunityMembership;
import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.Relationship;
import com.graphraglens.core.port.GraphStorePort;
import com.graphraglens.core.port.LlmPort;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Creates simple connected-component communities from the persisted knowledge graph.
 */
public class DetectCommunities {

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
        Collection<Entity> entities = graphStorePort.entities();
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }

        Map<String, Set<String>> adjacency = new HashMap<>();
        for (Entity entity : entities) {
            adjacency.computeIfAbsent(normalizedIdentity(entity), ignored -> new LinkedHashSet<>());
        }

        for (Relationship relationship : graphStorePort.relationships()) {
            String source = normalizedIdentity(relationship.source(), relationship.sourceType());
            String target = normalizedIdentity(relationship.target(), relationship.targetType());
            adjacency.computeIfAbsent(source, ignored -> new LinkedHashSet<>()).add(target);
            adjacency.computeIfAbsent(target, ignored -> new LinkedHashSet<>()).add(source);
        }

        Set<String> visited = new HashSet<>();
        List<Community> communities = new ArrayList<>();
        List<CommunityMembership> memberships = new ArrayList<>();
        int index = 1;

        for (Entity entity : entities) {
            String identity = normalizedIdentity(entity);
            if (visited.contains(identity)) {
                continue;
            }

            Queue<String> pending = new ArrayDeque<>();
            pending.add(identity);
            visited.add(identity);
            List<Entity> members = new ArrayList<>();
            members.add(entity);

            while (!pending.isEmpty()) {
                String current = pending.remove();
                for (Entity other : entities) {
                    if (normalizedIdentity(other).equals(current) && !members.contains(other)) {
                        members.add(other);
                    }
                }
                for (String neighbor : adjacency.getOrDefault(current, Set.of())) {
                    if (visited.add(neighbor)) {
                        pending.add(neighbor);
                    }
                    for (Entity other : entities) {
                        if (normalizedIdentity(other).equals(neighbor) && !members.contains(other)) {
                            members.add(other);
                        }
                    }
                }
            }

            String communityId = "community-" + index++;
            String summary = summarizeCommunity(members);
            communities.add(new Community(communityId, summary));
            for (Entity member : members) {
                memberships.add(new CommunityMembership(communityId, normalizedIdentity(member)));
            }
        }

        graphStorePort.persistCommunities(communities);
        graphStorePort.persistCommunityMemberships(memberships);

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

    private String summarizeCommunity(List<Entity> members) {
        if (llmPort != null) {
            return llmPort.summarizeCommunity(members);
        }
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

    private static String normalizedIdentity(Entity entity) {
        return normalizedIdentity(entity.name(), entity.type());
    }

    private static String normalizedIdentity(String name, String type) {
        String safeName = name == null ? "" : name.trim();
        String safeType = type == null ? "Unknown" : type.trim();
        return safeName.toLowerCase(Locale.ROOT) + "::" + safeType.toLowerCase(Locale.ROOT);
    }
}
