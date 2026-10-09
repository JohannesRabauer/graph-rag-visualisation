package dev.rabauer.graphrag.core.port;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.TextUnit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * A read view that keeps the whole-corpus reads of another
 * {@link GraphReadPort}: {@link #entities(String)}, {@link #relationships(String)},
 * {@link #communities(String)} and {@link #communityMemberships(String)} reach
 * the store once per corpus (and once more after {@link #invalidate(String)} or
 * when the entry is older than the maximum age) instead of on every question.
 * A matcher that scores all Entities, such as the keyword and identifier
 * matchers, then costs one read per corpus, and a store need not cache a whole
 * corpus itself.
 *
 * <p>Only these four whole-corpus reads are cached. The targeted reads
 * ({@link #entity}, {@link #entities(String, Collection)},
 * {@link #relationshipsTouching}), {@link #textUnit(String, String)},
 * {@link #textUnits(String)} and the similarity lookups go to the store every
 * time, so they stay as cheap and as fresh as the store makes them. A cached
 * collection is an unmodifiable snapshot, the same instance until it is
 * reloaded.
 *
 * <p><b>Writes are not seen.</b> After changing a corpus (ingest, import,
 * update, community detection), call {@link #invalidate(String)}, or give the
 * view a maximum age. Use one view per application, shared by the queries.
 * The view is thread-safe; two threads that miss at the same time may both
 * read the store.
 */
public final class CachingGraphReadPort implements GraphReadPort {

    private final GraphReadPort delegate;
    private final Duration maxAge;
    private final Clock clock;
    private final Map<String, Cached<Collection<Entity>>> entities = new ConcurrentHashMap<>();
    private final Map<String, Cached<Collection<Relationship>>> relationships = new ConcurrentHashMap<>();
    private final Map<String, Cached<Collection<Community>>> communities = new ConcurrentHashMap<>();
    private final Map<String, Cached<Collection<CommunityMembership>>> memberships = new ConcurrentHashMap<>();

    /** A view whose entries never expire: invalidate after writes. */
    public CachingGraphReadPort(GraphReadPort delegate) {
        this(delegate, null, Clock.systemUTC());
    }

    /** A view whose entries are read again once they are older than {@code maxAge} (null = never). */
    public CachingGraphReadPort(GraphReadPort delegate, Duration maxAge) {
        this(delegate, maxAge, Clock.systemUTC());
    }

    /**
     * @param maxAge how long a read is reused; null = until invalidated
     * @param clock  what "older than" is measured with
     * @throws IllegalArgumentException if {@code maxAge} is negative
     */
    public CachingGraphReadPort(GraphReadPort delegate, Duration maxAge, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (maxAge != null && maxAge.isNegative()) {
            throw new IllegalArgumentException("maxAge must not be negative, was " + maxAge);
        }
        this.maxAge = maxAge;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Forgets everything cached for {@code corpusId}; the next read goes to the store. */
    public void invalidate(String corpusId) {
        String key = key(corpusId);
        entities.remove(key);
        relationships.remove(key);
        communities.remove(key);
        memberships.remove(key);
    }

    /** Forgets everything cached. */
    public void invalidateAll() {
        entities.clear();
        relationships.clear();
        communities.clear();
        memberships.clear();
    }

    @Override
    public Collection<Entity> entities(String corpusId) {
        return cached(entities, corpusId, delegate::entities);
    }

    @Override
    public Collection<Relationship> relationships(String corpusId) {
        return cached(relationships, corpusId, delegate::relationships);
    }

    @Override
    public Collection<Community> communities(String corpusId) {
        return cached(communities, corpusId, delegate::communities);
    }

    @Override
    public Collection<CommunityMembership> communityMemberships(String corpusId) {
        return cached(memberships, corpusId, delegate::communityMemberships);
    }

    // -- Everything else is the store's -----------------------------------------------------

    @Override
    public Collection<TextUnit> textUnits(String corpusId) {
        return delegate.textUnits(corpusId);
    }

    @Override
    public Optional<TextUnit> textUnit(String corpusId, String textUnitId) {
        return delegate.textUnit(corpusId, textUnitId);
    }

    @Override
    public Collection<Entity> entities() {
        return delegate.entities();
    }

    @Override
    public Optional<Entity> entity(String corpusId, String identity) {
        return delegate.entity(corpusId, identity);
    }

    @Override
    public List<Entity> entities(String corpusId, Collection<String> identities) {
        return delegate.entities(corpusId, identities);
    }

    @Override
    public Collection<Relationship> relationships() {
        return delegate.relationships();
    }

    @Override
    public List<Relationship> relationshipsTouching(String corpusId, Collection<String> identities) {
        return delegate.relationshipsTouching(corpusId, identities);
    }

    @Override
    public Collection<Community> communities() {
        return delegate.communities();
    }

    @Override
    public Collection<CommunityMembership> communityMemberships() {
        return delegate.communityMemberships();
    }

    @Override
    public List<Entity> similarEntities(String corpusId, float[] query, int k) {
        return delegate.similarEntities(corpusId, query, k);
    }

    @Override
    public List<Community> similarCommunities(String corpusId, float[] query, int k) {
        return delegate.similarCommunities(corpusId, query, k);
    }

    // -- Cache ------------------------------------------------------------------------------

    private <T> Collection<T> cached(Map<String, Cached<Collection<T>>> cache, String corpusId,
                                     Function<String, Collection<T>> load) {
        String key = key(corpusId);
        Cached<Collection<T>> hit = cache.get(key);
        if (hit != null && !expired(hit)) {
            return hit.value();
        }
        Collection<T> read = load.apply(corpusId);
        Collection<T> snapshot = read == null ? List.of() : read.stream().filter(Objects::nonNull).toList();
        cache.put(key, new Cached<>(snapshot, clock.instant()));
        return snapshot;
    }

    private boolean expired(Cached<?> entry) {
        return maxAge != null && !entry.loadedAt().plus(maxAge).isAfter(clock.instant());
    }

    private static String key(String corpusId) {
        return corpusId == null ? "" : corpusId;
    }

    private record Cached<V>(V value, Instant loadedAt) {
    }
}
