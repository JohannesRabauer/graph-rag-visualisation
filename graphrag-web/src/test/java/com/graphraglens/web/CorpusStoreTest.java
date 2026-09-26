package com.graphraglens.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusStoreTest {

    @Test
    void aCorpusIsNotOfflineUnlessExplicitlyMarked() {
        CorpusStore store = new CorpusStore();

        assertFalse(store.isOffline("some-corpus"));
    }

    @Test
    void markOfflineFlagsOnlyTheGivenCorpusId() {
        CorpusStore store = new CorpusStore();

        store.markOffline("corpus-a");

        assertTrue(store.isOffline("corpus-a"));
        assertFalse(store.isOffline("corpus-b"));
    }

    @Test
    void markOfflineIgnoresNullOrBlankIdsWithoutThrowing() {
        CorpusStore store = new CorpusStore();

        store.markOffline(null);
        store.markOffline(" ");

        assertFalse(store.isOffline(null));
        assertFalse(store.isOffline(" "));
    }
}
