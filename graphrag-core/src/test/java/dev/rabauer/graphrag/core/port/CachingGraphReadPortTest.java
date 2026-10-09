package dev.rabauer.graphrag.core.port;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CachingGraphReadPortTest {

    /** A store that counts reads and whose content can change. */
    private static final class Store implements GraphReadPort {
        final List<String> reads = new ArrayList<>();
        List<Entity> entities = List.of(new Entity("A", "Class"), new Entity("B", "Class"));
        List<String> targeted = new ArrayList<>();

        @Override
        public Collection<Entity> entities(String corpusId) {
            reads.add("entities:" + corpusId);
            return entities;
        }

        @Override
        public Collection<Relationship> relationships(String corpusId) {
            reads.add("relationships:" + corpusId);
            return List.of(new Relationship("A", "Class", "CALLS", "B", "Class"));
        }

        @Override
        public Collection<Community> communities(String corpusId) {
            reads.add("communities:" + corpusId);
            return List.of(new Community("c1", "t", "s"));
        }

        @Override
        public Collection<CommunityMembership> communityMemberships(String corpusId) {
            reads.add("memberships:" + corpusId);
            return List.of(new CommunityMembership("c1", "a::class"));
        }

        @Override
        public List<Entity> entities(String corpusId, Collection<String> identities) {
            targeted.add(corpusId + ":" + identities);
            return entities.stream().filter(entity -> identities.contains(entity.normalizedIdentity())).toList();
        }
    }

    /** A clock the test moves. */
    private static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-10-09T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void eachWholeCorpusReadReachesTheStoreOnce() {
        Store store = new Store();
        CachingGraphReadPort view = new CachingGraphReadPort(store);

        for (int i = 0; i < 3; i++) {
            assertEquals(2, view.entities("c").size());
            assertEquals(1, view.relationships("c").size());
            assertEquals(1, view.communities("c").size());
            assertEquals(1, view.communityMemberships("c").size());
        }

        assertEquals(List.of("entities:c", "relationships:c", "communities:c", "memberships:c"), store.reads);
    }

    @Test
    void theSameSnapshotIsReturnedWhileItIsCached() {
        CachingGraphReadPort view = new CachingGraphReadPort(new Store());

        assertSame(view.entities("c"), view.entities("c"));
    }

    @Test
    void corporaAreCachedSeparately() {
        Store store = new Store();
        CachingGraphReadPort view = new CachingGraphReadPort(store);

        view.entities("one");
        view.entities("two");
        view.entities("one");

        assertEquals(List.of("entities:one", "entities:two"), store.reads);
    }

    @Test
    void invalidatingACorpusReadsItAgain() {
        Store store = new Store();
        CachingGraphReadPort view = new CachingGraphReadPort(store);
        view.entities("one");
        view.entities("two");
        store.entities = List.of(new Entity("A", "Class"));

        view.invalidate("one");

        assertEquals(1, view.entities("one").size());
        assertEquals(2, view.entities("two").size(), "the other corpus is still cached");
        view.invalidateAll();
        assertEquals(1, view.entities("two").size());
    }

    @Test
    void anEntryExpiresAfterItsMaximumAge() {
        Store store = new Store();
        MovingClock clock = new MovingClock();
        CachingGraphReadPort view = new CachingGraphReadPort(store, Duration.ofSeconds(30), clock);

        view.entities("c");
        clock.now = clock.now.plusSeconds(29);
        view.entities("c");
        assertEquals(1, store.reads.size());
        clock.now = clock.now.plusSeconds(2);
        view.entities("c");

        assertEquals(2, store.reads.size());
    }

    @Test
    void targetedReadsGoToTheStoreUncached() {
        Store store = new Store();
        CachingGraphReadPort view = new CachingGraphReadPort(store);

        assertEquals(1, view.entities("c", List.of("a::class")).size());
        assertEquals(1, view.entities("c", List.of("a::class")).size());

        assertEquals(2, store.targeted.size());
        assertEquals(List.of(), store.reads, "no whole-corpus read was needed");
    }

    @Test
    void theCachedCollectionsCannotBeChangedByCallers() {
        CachingGraphReadPort view = new CachingGraphReadPort(new Store());

        assertThrows(UnsupportedOperationException.class, () -> view.entities("c").clear());
    }

    @Test
    void aNegativeMaximumAgeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new CachingGraphReadPort(new Store(), Duration.ofSeconds(-1), Clock.systemUTC()));
    }
}
