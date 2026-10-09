package dev.rabauer.graphrag.core.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * How big a Community is and what it consists of, handed to
 * {@code LlmPort.summarizeCommunity} next to the (capped) member list: a
 * summary of 25 listed members is far better when the model also knows the
 * Community has 130 members, mostly in two modules.
 *
 * @param memberCount     how many distinct member Entities the Community has
 * @param listedMembers   how many of them the summary call lists (at most
 *                        {@code memberCount})
 * @param attributeCounts per configured Entity attribute (for example
 *                        {@code module} or {@code package}), the member count
 *                        by value, most members first, ties by value; counts
 *                        cover all members, not only the listed ones;
 *                        attributes no member has are left out; never null
 */
public record CommunityStats(int memberCount, int listedMembers, Map<String, Map<String, Integer>> attributeCounts) {

    public CommunityStats {
        memberCount = Math.max(0, memberCount);
        listedMembers = Math.max(0, Math.min(listedMembers, memberCount));
        Map<String, Map<String, Integer>> copy = new LinkedHashMap<>();
        if (attributeCounts != null) {
            attributeCounts.forEach((key, counts) -> {
                if (key != null && counts != null && !counts.isEmpty()) {
                    copy.put(key, Collections.unmodifiableMap(new LinkedHashMap<>(counts)));
                }
            });
        }
        attributeCounts = Collections.unmodifiableMap(copy);
    }

    /** All members listed, no attribute counts. */
    public static CommunityStats of(Collection<Entity> members) {
        return of(members, Integer.MAX_VALUE, List.of());
    }

    /**
     * The statistics of {@code members}.
     *
     * @param members       every member of the Community
     * @param listedMembers how many of them the summary call lists
     * @param attributeKeys the Entity attributes to count by value; blank
     *                      values are not counted
     */
    public static CommunityStats of(Collection<Entity> members, int listedMembers, Collection<String> attributeKeys) {
        Set<String> identities = new LinkedHashSet<>();
        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        List<Entity> distinct = new ArrayList<>();
        for (Entity member : members == null ? List.<Entity>of() : members) {
            if (member != null && identities.add(member.normalizedIdentity())) {
                distinct.add(member);
            }
        }
        for (String key : attributeKeys == null ? List.<String>of() : attributeKeys) {
            Map<String, Integer> byValue = new LinkedHashMap<>();
            for (Entity member : distinct) {
                member.attribute(key).map(String::trim).filter(value -> !value.isEmpty())
                        .ifPresent(value -> byValue.merge(value, 1, Integer::sum));
            }
            if (!byValue.isEmpty()) {
                Map<String, Integer> sorted = new LinkedHashMap<>();
                byValue.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                                .thenComparing(Map.Entry.comparingByKey()))
                        .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
                counts.put(Objects.requireNonNull(key), sorted);
            }
        }
        return new CommunityStats(distinct.size(), listedMembers, counts);
    }

    /** Whether there is more to tell than the listed members: members left out, or attribute counts. */
    public boolean isInformative() {
        return memberCount > listedMembers || !attributeCounts.isEmpty();
    }
}
