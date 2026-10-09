package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.community.CommunityDetector;
import dev.rabauer.graphrag.core.community.GraphCommunities;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.CommunityStats;
import dev.rabauer.graphrag.core.domain.CommunitySummary;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.usecase.CommunityDetectionResult.SummaryOutcome;
import dev.rabauer.graphrag.core.usecase.CommunityDetectionResult.SummaryStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

/**
 * Detects Communities in a corpus's persisted knowledge graph, summarizes and
 * persists them, and reports each one to an optional callback.
 *
 * <p>The grouping itself comes from {@link GraphStorePort#detectCommunities(String)}
 * (by default the core's modularity-based detector, or a native algorithm such
 * as GDS Leiden when the graph store provides one), or from
 * {@link Options#detector()} when set, which runs over the store's Entities and
 * Relationships instead. Whatever order the grouping returns, Community ids are
 * always {@code community-1..n}, assigned in the order of each group's first
 * member in {@link GraphStorePort#entities(String)}, and members keep that
 * entity order, so ids are deterministic for any adapter. An Entity the
 * grouping leaves out of every group is treated as its own single-member
 * group.</p>
 *
 * <p>Only groups with at least {@linkplain #MIN_COMMUNITY_SIZE a minimum number}
 * of distinct member identities (default {@value #MIN_COMMUNITY_SIZE}) become
 * Communities. Smaller groups (isolated entities, isolated pairs) produce no
 * {@link Community}, no {@link CommunityMembership}, no LLM summary call and no
 * callback; their Entities stay ordinary Entities in the graph, still reachable
 * by Local Search. The ids {@code community-1..n} are contiguous over the kept
 * groups, in the same deterministic order.</p>
 *
 * <p>Summaries ({@link Options}): by default one at a time, in Community
 * order, and the first failure propagates. Options add bounded parallelism
 * (only for a port whose {@link LlmPort#summarizesCommunities()} is true), a
 * per-run budget (at most {@link Options#maxSummaries()} port calls and
 * {@link Options#maxWallTime()}) that yields a valid
 * {@link CommunityDetectionResult.Status#PARTIAL partial} result, per-item
 * failure isolation ({@link FailurePolicy#ISOLATE_ITEM}), and summary reuse by
 * content hash for incremental re-runs. Every Community is persisted in every
 * case; one whose summary failed or was skipped gets the deterministic title
 * and summary.</p>
 *
 * <p><b>What a summary is written from.</b> The port gets, per Community, the
 * first {@link Options#maxSummaryMembers()} members (default
 * {@value #MAX_SUMMARY_MEMBERS}) <em>in stored entity order</em>, so for a large
 * Community the summary covers whichever members the store lists first.
 * {@link Options#summaryMemberOrder()} sorts the members first (for example by
 * a ranking attribute) and so chooses which ones are summarized; it changes
 * nothing about the Community's membership. The port also gets a
 * {@link CommunityStats}: the Community's full size and its member counts by
 * {@link Options#statsAttributes() attribute} (default {@code module} and
 * {@code package}), through
 * {@link LlmPort#summarizeCommunity(Collection, Collection, CommunityStats)}.</p>
 *
 * <p><b>Flat or hierarchical.</b> By default the Communities are one flat
 * partition (the store's, or the detector's coarsest level).
 * {@link Options#hierarchyLevel()} picks another level of the detector's
 * hierarchy (for the modularity detector, {@code detectHierarchy}: level 0 is
 * the finest), and {@link Options#maxCommunitySize()} splits oversized
 * Communities along the finer levels. Either one makes the run use the
 * detector's hierarchy; without a {@link Options#detector()} that is the core's
 * modularity detector, not the store's own grouping.</p>
 */
public class DetectCommunities {

    /**
     * By default at most this many members are handed to the LLM per Community,
     * in stored entity order ({@link Options#maxSummaryMembers()},
     * {@link Options#summaryMemberOrder()}).
     */
    static final int MAX_SUMMARY_MEMBERS = 25;
    /** At most this many internal Relationships (highest weight first) are handed to the LLM per Community. */
    static final int MAX_SUMMARY_RELATIONSHIPS = 30;
    /** Default minimum number of distinct member identities a group needs to become a Community. */
    public static final int MIN_COMMUNITY_SIZE = 3;
    /** Community attribute holding the content hash used for summary reuse. */
    public static final String CONTENT_HASH_ATTRIBUTE = "contentHash";
    /** Community attribute holding the {@link SummaryStatus} of the stored summary (with summary reuse on). */
    public static final String SUMMARY_STATUS_ATTRIBUTE = "summaryStatus";

    private final GraphStorePort graphStorePort;
    private final LlmPort llmPort;
    private final Options options;

    public DetectCommunities(GraphStorePort graphStorePort) {
        this(graphStorePort, null);
    }

    public DetectCommunities(GraphStorePort graphStorePort, LlmPort llmPort) {
        this(graphStorePort, llmPort, MIN_COMMUNITY_SIZE);
    }

    /**
     * @param minCommunitySize the minimum number of distinct member identities a
     *                         group needs to become a Community; {@code 1} keeps
     *                         every group, singletons included
     * @throws IllegalArgumentException if {@code minCommunitySize} is below 1
     */
    public DetectCommunities(GraphStorePort graphStorePort, LlmPort llmPort, int minCommunitySize) {
        this(graphStorePort, llmPort, Options.defaults().withMinCommunitySize(minCommunitySize));
    }

    /** A run configured by {@code options} (null means {@link Options#defaults()}). */
    public DetectCommunities(GraphStorePort graphStorePort, LlmPort llmPort, Options options) {
        this.graphStorePort = graphStorePort;
        this.llmPort = llmPort;
        this.options = options == null ? Options.defaults() : options;
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
        return detect(corpus.id(), onCommunityDetected);
    }

    /** {@link #detect(Corpus)} for a corpus known only by its id (e.g. an imported graph). */
    public List<Community> detect(String corpusId) {
        return detect(corpusId, null);
    }

    /** {@link #detect(Corpus, BiConsumer)} for a corpus known only by its id. */
    public List<Community> detect(String corpusId, BiConsumer<Community, List<String>> onCommunityDetected) {
        return run(corpusId, null, onCommunityDetected).communities();
    }

    public void run(Corpus corpus) {
        detect(corpus);
    }

    public void run(Corpus corpus, BiConsumer<Community, List<String>> onCommunityDetected) {
        detect(corpus, onCommunityDetected);
    }

    /** {@link #run(String, ProgressListener, BiConsumer)} without callbacks. */
    public CommunityDetectionResult run(String corpusId) {
        return run(corpusId, null, null);
    }

    /**
     * Detects, summarizes and persists the Communities of {@code corpusId}.
     *
     * @param progress            told after each summary, in Community order; may be null
     * @param onCommunityDetected told once per Community after everything is
     *                            persisted; may be null
     * @return the persisted Communities and each one's summary outcome
     */
    public CommunityDetectionResult run(String corpusId, ProgressListener progress,
                                        BiConsumer<Community, List<String>> onCommunityDetected) {
        return run(corpusId, progress, onCommunityDetected,
                options.reuseSummaries() ? reusableSummaries(corpusId) : Map.of());
    }

    /** {@link #recompute(String, ProgressListener, BiConsumer)} without callbacks. */
    public CommunityDetectionResult recompute(String corpusId) {
        return recompute(corpusId, null, null);
    }

    /**
     * Re-detects from scratch: reads the stored Communities' reusable
     * summaries (with {@link Options#reuseSummaries()}), deletes every
     * Community of the corpus ({@code GraphWritePort.deleteCommunities}), then
     * runs. Use it after incremental changes, so no stale Community or
     * membership survives and unchanged Communities keep their summaries.
     *
     * @throws UnsupportedOperationException if the store cannot delete Communities
     */
    public CommunityDetectionResult recompute(String corpusId, ProgressListener progress,
                                              BiConsumer<Community, List<String>> onCommunityDetected) {
        Map<String, Community> reusable = options.reuseSummaries() ? reusableSummaries(corpusId) : Map.of();
        graphStorePort.deleteCommunities(corpusId);
        return run(corpusId, progress, onCommunityDetected, reusable);
    }

    private CommunityDetectionResult run(String corpusId, ProgressListener progress,
                                         BiConsumer<Community, List<String>> onCommunityDetected,
                                         Map<String, Community> reusable) {
        long started = System.nanoTime();
        Collection<Entity> stored = graphStorePort.entities(corpusId);
        List<Entity> entities = stored == null ? List.of() : stored.stream().filter(Objects::nonNull).toList();
        if (entities.isEmpty()) {
            return emptyResult(corpusId, started);
        }

        List<Relationship> allRelationships = storedRelationships(corpusId);
        List<List<String>> identityGroups = identityGroups(corpusId, entities, allRelationships);
        List<List<Entity>> groups = orderedGroups(entities, identityGroups).stream()
                .filter(members -> distinctIdentities(members) >= options.minCommunitySize())
                .toList();
        if (groups.isEmpty()) {
            return emptyResult(corpusId, started);
        }

        List<Draft> drafts = new ArrayList<>();
        int index = 1;
        for (List<Entity> members : groups) {
            List<Entity> listed = options.summaryMemberOrder() == null ? members
                    : members.stream().sorted(options.summaryMemberOrder()).toList();
            List<Entity> capped = listed.stream().limit(options.maxSummaryMembers()).toList();
            drafts.add(new Draft("community-" + index++, members, capped,
                    internalRelationships(capped, allRelationships),
                    CommunityStats.of(members, capped.size(), options.statsAttributes())));
        }

        List<Summarized> summarized = summarizeAll(drafts, started, progress, reusable);

        List<Community> communities = new ArrayList<>();
        List<CommunityMembership> memberships = new ArrayList<>();
        List<SummaryOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            Summarized result = summarized.get(i);
            Community community = new Community(draft.id(), result.summary().title(), result.summary().summary());
            if (options.reuseSummaries()) {
                community = community.withAttributes(Map.of(
                        CONTENT_HASH_ATTRIBUTE, contentHash(draft),
                        SUMMARY_STATUS_ATTRIBUTE, result.status().name()));
            }
            communities.add(community);
            outcomes.add(new SummaryOutcome(draft.id(), result.status(), result.error()));
            for (Entity member : draft.members()) {
                memberships.add(new CommunityMembership(draft.id(), member.normalizedIdentity()));
            }
        }

        graphStorePort.persistCommunities(corpusId, communities);
        graphStorePort.persistCommunityMemberships(corpusId, memberships);

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

        boolean partial = outcomes.stream().anyMatch(outcome -> outcome.status() == SummaryStatus.FAILED
                || outcome.status() == SummaryStatus.SKIPPED_BUDGET);
        return new CommunityDetectionResult(corpusId,
                partial ? CommunityDetectionResult.Status.PARTIAL : CommunityDetectionResult.Status.COMPLETE,
                communities, outcomes, elapsedMs(started));
    }

    /**
     * The grouping: the store's own, or the {@link Options#detector()} flat; with
     * a {@linkplain Options#hierarchyLevel() level} or a
     * {@linkplain Options#maxCommunitySize() maximum size} chosen, one
     * partition cut out of the detector's hierarchy (the core's modularity
     * detector when no detector is set, since a store's native grouping has no
     * levels).
     */
    private List<List<String>> identityGroups(String corpusId, List<Entity> entities,
                                              List<Relationship> relationships) {
        if (options.usesHierarchy()) {
            CommunityDetector detector = options.detector() == null ? GraphCommunities.defaultDetector()
                    : options.detector();
            return GraphCommunities.select(GraphCommunities.detectHierarchy(detector, entities, relationships),
                    options.hierarchyLevel(), options.maxCommunitySize());
        }
        return options.detector() == null
                ? graphStorePort.detectCommunities(corpusId)
                : GraphCommunities.detect(options.detector(), entities, relationships);
    }

    private static CommunityDetectionResult emptyResult(String corpusId, long started) {
        return new CommunityDetectionResult(corpusId, CommunityDetectionResult.Status.EMPTY, List.of(), List.of(),
                elapsedMs(started));
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    // -- Summaries ----------------------------------------------------------------

    /** One detected group before it is summarized. */
    private record Draft(String id, List<Entity> members, List<Entity> cappedMembers,
                         List<Relationship> internalRelationships, CommunityStats stats) {
    }

    /** One group's summary and how it was obtained. */
    private record Summarized(CommunitySummary summary, SummaryStatus status, String error) {
    }

    private List<Summarized> summarizeAll(List<Draft> drafts, long started, ProgressListener progress,
                                          Map<String, Community> reusable) {
        long deadline = options.maxWallTime() == null ? Long.MAX_VALUE
                : started + options.maxWallTime().toNanos();
        Summarized[] results = new Summarized[drafts.size()];
        List<Integer> toGenerate = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            Community previous = reusable.get(contentHash(draft));
            if (previous != null) {
                results[i] = new Summarized(new CommunitySummary(previous.title(), previous.summary()),
                        SummaryStatus.REUSED, "");
            } else if (llmPort == null) {
                results[i] = new Summarized(deterministic(draft), SummaryStatus.DETERMINISTIC, "");
            } else if (toGenerate.size() >= options.maxSummaries()) {
                results[i] = skipped(draft);
            } else {
                toGenerate.add(i);
            }
        }

        boolean parallel = options.parallelism() > 1 && llmPort != null && llmPort.summarizesCommunities()
                && toGenerate.size() > 1;
        if (parallel) {
            summarizeInParallel(drafts, toGenerate, results, deadline);
        } else {
            for (int i : toGenerate) {
                results[i] = System.nanoTime() >= deadline ? skipped(drafts.get(i)) : summarizeOne(drafts.get(i));
            }
        }

        if (progress != null) {
            for (int i = 0; i < drafts.size(); i++) {
                progress.onSummary(i + 1, drafts.size(), drafts.get(i).id(), results[i].status());
            }
        }
        return List.of(results);
    }

    private void summarizeInParallel(List<Draft> drafts, List<Integer> toGenerate, Summarized[] results,
                                     long deadline) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(options.parallelism(), toGenerate.size()),
                runnable -> {
                    Thread thread = new Thread(runnable, "graphrag-community-summary");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            Map<Integer, Future<Summarized>> futures = new LinkedHashMap<>();
            for (int i : toGenerate) {
                Draft draft = drafts.get(i);
                futures.put(i, executor.submit(() -> System.nanoTime() >= deadline ? skipped(draft)
                        : summarizeOne(draft)));
            }
            for (Map.Entry<Integer, Future<Summarized>> entry : futures.entrySet()) {
                Draft draft = drafts.get(entry.getKey());
                results[entry.getKey()] = await(entry.getValue(), draft, deadline);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private Summarized await(Future<Summarized> future, Draft draft, long deadline) {
        try {
            if (deadline == Long.MAX_VALUE) {
                return future.get();
            }
            return future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException | CancellationException e) {
            future.cancel(true);
            return skipped(draft);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return skipped(draft);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    /**
     * Summarizes one Community from its first {@link Options#maxSummaryMembers()}
     * members (default {@value #MAX_SUMMARY_MEMBERS}, in stored entity order unless
     * {@link Options#summaryMemberOrder()} sorts them) and its internal Relationships
     * (both endpoints among those passed members), the {@value #MAX_SUMMARY_RELATIONSHIPS}
     * highest-weight first, ties in stored order, together with the
     * {@link CommunityStats} of the whole Community.
     * A port returning null gets the deterministic title and summary (no second call).
     */
    private Summarized summarizeOne(Draft draft) {
        CommunitySummary generated;
        try {
            generated = llmPort.summarizeCommunity(draft.cappedMembers(), draft.internalRelationships(),
                    draft.stats());
        } catch (RuntimeException e) {
            if (options.failurePolicy() == FailurePolicy.FAIL_RUN) {
                throw e;
            }
            return new Summarized(deterministic(draft), SummaryStatus.FAILED,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        if (generated == null) {
            return new Summarized(deterministic(draft), SummaryStatus.DETERMINISTIC, "");
        }
        // A port without a model behind its summaries (LlmPort.none(), the defaults) writes deterministic ones.
        return new Summarized(generated,
                llmPort.summarizesCommunities() ? SummaryStatus.GENERATED : SummaryStatus.DETERMINISTIC, "");
    }

    private static Summarized skipped(Draft draft) {
        return new Summarized(deterministic(draft), SummaryStatus.SKIPPED_BUDGET, "");
    }

    private static CommunitySummary deterministic(Draft draft) {
        return new CommunitySummary(CommunitySummary.deterministicTitle(draft.cappedMembers()),
                fallbackSummary(draft.cappedMembers()));
    }

    /**
     * Stored Communities whose summary may be reused, by content hash: only
     * ones that carry a hash and whose stored summary was written by the port
     * (generated or itself reused), so a fallback summary is retried.
     */
    private Map<String, Community> reusableSummaries(String corpusId) {
        Map<String, Community> byHash = new HashMap<>();
        Collection<Community> stored = graphStorePort.communities(corpusId);
        if (stored == null) {
            return byHash;
        }
        for (Community community : stored) {
            if (community == null) {
                continue;
            }
            String hash = community.attributes().get(CONTENT_HASH_ATTRIBUTE);
            String status = community.attributes().get(SUMMARY_STATUS_ATTRIBUTE);
            boolean written = SummaryStatus.GENERATED.name().equals(status)
                    || SummaryStatus.REUSED.name().equals(status);
            if (hash != null && written) {
                byHash.putIfAbsent(hash, community);
            }
        }
        return byHash;
    }

    /**
     * SHA-256 over everything a summary is written from: the member
     * identities and descriptions and the internal Relationships handed to
     * the port, each sorted, so the hash ignores storage order.
     */
    static String contentHash(List<Entity> cappedMembers, List<Relationship> internalRelationships) {
        return contentHash(cappedMembers, internalRelationships, null);
    }

    /** As above, plus the size and attribute counts when they say more than the listed members do. */
    static String contentHash(List<Entity> cappedMembers, List<Relationship> internalRelationships,
                              CommunityStats stats) {
        List<String> lines = new ArrayList<>();
        if (stats != null && stats.isInformative()) {
            lines.add("S|" + stats.memberCount() + "|" + stats.attributeCounts());
        }
        for (Entity member : cappedMembers) {
            lines.add("E|" + member.normalizedIdentity() + "|" + member.description());
        }
        for (Relationship relationship : internalRelationships) {
            lines.add("R|" + relationship.sourceIdentity() + "|" + relationship.type() + "|"
                    + relationship.targetIdentity() + "|" + relationship.weight() + "|" + relationship.description());
        }
        lines.sort(Comparator.naturalOrder());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                digest.update(line.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String contentHash(Draft draft) {
        return contentHash(draft.cappedMembers(), draft.internalRelationships(), draft.stats());
    }

    // -- Grouping -------------------------------------------------------------------

    private static long distinctIdentities(List<Entity> members) {
        return members.stream().map(Entity::normalizedIdentity).distinct().count();
    }

    private List<Relationship> storedRelationships(String corpusId) {
        Collection<Relationship> stored = graphStorePort.relationships(corpusId);
        if (stored == null) {
            return List.of();
        }
        return stored.stream().filter(Objects::nonNull).toList();
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

    static String fallbackSummary(List<Entity> members) {
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

    /** Told after each Community summary, in Community order. */
    @FunctionalInterface
    public interface ProgressListener {
        /**
         * @param done        how many Communities are summarized so far (1-based)
         * @param total       how many Communities the run has
         * @param communityId the Community just summarized
         * @param status      how its summary was obtained
         */
        void onSummary(int done, int total, String communityId, SummaryStatus status);
    }

    /**
     * How a run detects and summarizes.
     *
     * @param minCommunitySize   the minimum number of distinct members (at least 1)
     * @param detector           the grouping to use instead of
     *                           {@link GraphStorePort#detectCommunities(String)};
     *                           null uses the store's
     * @param parallelism        the most summaries in flight at once (at least 1);
     *                           only used when the port's
     *                           {@link LlmPort#summarizesCommunities()} is true
     * @param maxSummaries       the most port calls per run (at least 0); the rest
     *                           are {@link SummaryStatus#SKIPPED_BUDGET}
     * @param maxWallTime        the run's time budget; summaries not finished by
     *                           then are {@link SummaryStatus#SKIPPED_BUDGET}; null
     *                           means unlimited
     * @param failurePolicy      whether a failing summary stops the run
     * @param reuseSummaries     whether to reuse a stored Community's summary when
     *                           its content hash matches (and record the hash and
     *                           summary status as Community attributes)
     * @param summaryMemberOrder which members of a Community the summary is
     *                           written from, and in which order: the members
     *                           are sorted with it (stable) and the first
     *                           {@code maxSummaryMembers} are handed to the port.
     *                           null (the default) keeps the stored entity
     *                           order, so the summary covers the first members
     *                           stored. Only the summary is affected: a
     *                           Community still has every member, in entity
     *                           order
     * @param maxSummaryMembers  the most members handed to the port per Community
     *                           (at least 1; default {@value DetectCommunities#MAX_SUMMARY_MEMBERS})
     * @param hierarchyLevel     which level of the detector's hierarchy becomes
     *                           the Communities: 0 is the finest, a negative
     *                           value (the default, -1) the coarsest, a level
     *                           beyond the last also the coarsest. With a level
     *                           (or a {@code maxCommunitySize}) and no
     *                           {@code detector}, the core's modularity detector
     *                           is used instead of the store's own grouping,
     *                           because that one has no levels
     * @param maxCommunitySize   the most members per Community (at least 0; 0 =
     *                           no limit): a larger group is replaced by the parts
     *                           of the next finer level, down to level 0, where
     *                           it stays whole
     * @param statsAttributes    the Entity attributes counted for
     *                           {@link CommunityStats} (default {@code module} and
     *                           {@code package}); an attribute no member has is
     *                           left out
     */
    public record Options(int minCommunitySize, CommunityDetector detector, int parallelism, int maxSummaries,
                          Duration maxWallTime, FailurePolicy failurePolicy, boolean reuseSummaries,
                          Comparator<Entity> summaryMemberOrder, int maxSummaryMembers, int hierarchyLevel,
                          int maxCommunitySize, List<String> statsAttributes) {

        /** The attributes counted by default. */
        public static final List<String> DEFAULT_STATS_ATTRIBUTES = List.of("module", "package");

        public Options {
            if (minCommunitySize < 1) {
                throw new IllegalArgumentException("minCommunitySize must be at least 1, was " + minCommunitySize);
            }
            if (parallelism < 1) {
                throw new IllegalArgumentException("parallelism must be at least 1, was " + parallelism);
            }
            if (maxSummaries < 0) {
                throw new IllegalArgumentException("maxSummaries must not be negative, was " + maxSummaries);
            }
            if (maxWallTime != null && maxWallTime.isNegative()) {
                throw new IllegalArgumentException("maxWallTime must not be negative, was " + maxWallTime);
            }
            if (maxSummaryMembers < 1) {
                throw new IllegalArgumentException("maxSummaryMembers must be at least 1, was " + maxSummaryMembers);
            }
            if (hierarchyLevel < -1) {
                throw new IllegalArgumentException("hierarchyLevel must be -1 (coarsest) or at least 0, was "
                        + hierarchyLevel);
            }
            if (maxCommunitySize < 0) {
                throw new IllegalArgumentException("maxCommunitySize must not be negative, was " + maxCommunitySize);
            }
            failurePolicy = failurePolicy == null ? FailurePolicy.FAIL_RUN : failurePolicy;
            statsAttributes = statsAttributes == null ? DEFAULT_STATS_ATTRIBUTES : List.copyOf(statsAttributes);
        }

        /**
         * The options before member order, hierarchy and statistics existed:
         * stored member order, 25 members, the coarsest level, no size limit and
         * the default statistics attributes.
         */
        public Options(int minCommunitySize, CommunityDetector detector, int parallelism, int maxSummaries,
                       Duration maxWallTime, FailurePolicy failurePolicy, boolean reuseSummaries) {
            this(minCommunitySize, detector, parallelism, maxSummaries, maxWallTime, failurePolicy, reuseSummaries,
                    null, MAX_SUMMARY_MEMBERS, -1, 0, DEFAULT_STATS_ATTRIBUTES);
        }

        /**
         * The behaviour of the existing constructors: minimum size
         * {@value DetectCommunities#MIN_COMMUNITY_SIZE}, the store's grouping,
         * sequential, unlimited, failures stop the run, no reuse, summaries from
         * the first {@value DetectCommunities#MAX_SUMMARY_MEMBERS} members in stored
         * order, the coarsest level, no size limit.
         */
        public static Options defaults() {
            return new Options(MIN_COMMUNITY_SIZE, null, 1, Integer.MAX_VALUE, null, FailurePolicy.FAIL_RUN, false);
        }

        /** Whether a hierarchy level or a maximum size is chosen, so the grouping must come from a hierarchy. */
        boolean usesHierarchy() {
            return hierarchyLevel >= 0 || maxCommunitySize > 0;
        }

        public Options withMinCommunitySize(int value) {
            return new Options(value, detector, parallelism, maxSummaries, maxWallTime, failurePolicy,
                    reuseSummaries, summaryMemberOrder, maxSummaryMembers, hierarchyLevel, maxCommunitySize,
                    statsAttributes);
        }

        public Options withDetector(CommunityDetector value) {
            return new Options(minCommunitySize, value, parallelism, maxSummaries, maxWallTime, failurePolicy,
                    reuseSummaries, summaryMemberOrder, maxSummaryMembers, hierarchyLevel, maxCommunitySize,
                    statsAttributes);
        }

        public Options withParallelism(int value) {
            return new Options(minCommunitySize, detector, value, maxSummaries, maxWallTime, failurePolicy,
                    reuseSummaries, summaryMemberOrder, maxSummaryMembers, hierarchyLevel, maxCommunitySize,
                    statsAttributes);
        }

        public Options withMaxSummaries(int value) {
            return new Options(minCommunitySize, detector, parallelism, value, maxWallTime, failurePolicy,
                    reuseSummaries, summaryMemberOrder, maxSummaryMembers, hierarchyLevel, maxCommunitySize,
                    statsAttributes);
        }

        public Options withMaxWallTime(Duration value) {
            return new Options(minCommunitySize, detector, parallelism, maxSummaries, value, failurePolicy,
                    reuseSummaries, summaryMemberOrder, maxSummaryMembers, hierarchyLevel, maxCommunitySize,
                    statsAttributes);
        }

        public Options withFailurePolicy(FailurePolicy value) {
            return new Options(minCommunitySize, detector, parallelism, maxSummaries, maxWallTime, value,
                    reuseSummaries, summaryMemberOrder, maxSummaryMembers, hierarchyLevel, maxCommunitySize,
                    statsAttributes);
        }

        public Options withReuseSummaries(boolean value) {
            return new Options(minCommunitySize, detector, parallelism, maxSummaries, maxWallTime, failurePolicy,
                    value, summaryMemberOrder, maxSummaryMembers, hierarchyLevel, maxCommunitySize,
                    statsAttributes);
        }

        /** Sorts the members before the first {@link #maxSummaryMembers()} are taken; null = stored order. */
        public Options withSummaryMemberOrder(Comparator<Entity> value) {
            return new Options(minCommunitySize, detector, parallelism, maxSummaries, maxWallTime, failurePolicy,
                    reuseSummaries, value, maxSummaryMembers, hierarchyLevel, maxCommunitySize, statsAttributes);
        }

        public Options withMaxSummaryMembers(int value) {
            return new Options(minCommunitySize, detector, parallelism, maxSummaries, maxWallTime, failurePolicy,
                    reuseSummaries, summaryMemberOrder, value, hierarchyLevel, maxCommunitySize, statsAttributes);
        }

        /** The hierarchy level the Communities come from: 0 = finest, -1 = coarsest. */
        public Options withHierarchyLevel(int value) {
            return new Options(minCommunitySize, detector, parallelism, maxSummaries, maxWallTime, failurePolicy,
                    reuseSummaries, summaryMemberOrder, maxSummaryMembers, value, maxCommunitySize, statsAttributes);
        }

        /** The most members per Community; larger groups are split along the hierarchy; 0 = no limit. */
        public Options withMaxCommunitySize(int value) {
            return new Options(minCommunitySize, detector, parallelism, maxSummaries, maxWallTime, failurePolicy,
                    reuseSummaries, summaryMemberOrder, maxSummaryMembers, hierarchyLevel, value, statsAttributes);
        }

        public Options withStatsAttributes(List<String> value) {
            return new Options(minCommunitySize, detector, parallelism, maxSummaries, maxWallTime, failurePolicy,
                    reuseSummaries, summaryMemberOrder, maxSummaryMembers, hierarchyLevel, maxCommunitySize, value);
        }
    }
}
