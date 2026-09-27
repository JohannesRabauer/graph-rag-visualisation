package com.graphraglens.web;

import io.graphrag.core.domain.Corpus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DemoDatasetServiceTest {

    private final DemoDatasetService service = new DemoDatasetService();

    @Test
    void offlineAndLiveSherlockCorporaShareTheSameDocumentsButDistinctNames() {
        Corpus live = service.createSherlockCorpus();
        Corpus offline = service.createOfflineSherlockCorpus();

        assertEquals("Sherlock Holmes — Demo Dataset", live.name());
        assertEquals("Sherlock Holmes — Offline Demo (no API calls)", offline.name());
        assertNotEquals(live.name(), offline.name());
        assertEquals(live.documentNames(), offline.documentNames());
        assertEquals(
                live.documents().stream().map(doc -> doc.content()).toList(),
                offline.documents().stream().map(doc -> doc.content()).toList());
    }

    @Test
    void eachCallMintsAFreshCorpusId() {
        Corpus first = service.createOfflineSherlockCorpus();
        Corpus second = service.createOfflineSherlockCorpus();

        assertNotEquals(first.id(), second.id());
    }
}
