package com.graphraglens.web;

import com.graphraglens.core.domain.Corpus;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of Corpora, keyed by id. Corpus identity lives here in
 * {@code graphrag-web}, not in Neo4j — the architecture spine treats a
 * Corpus as bookkeeping for the web layer, distinct from the
 * Entities/Relationships extracted from it (which do become Neo4j nodes,
 * starting Story 2.4). Held in memory only — no disk/DB persistence,
 * matching NFR3's local-only, no-restart-durability scope.
 */
@Component
public class CorpusStore {

    private final ConcurrentHashMap<String, Corpus> corpora = new ConcurrentHashMap<>();

    public void put(Corpus corpus) {
        corpora.put(corpus.id(), corpus);
    }

    public Optional<Corpus> get(String id) {
        return Optional.ofNullable(corpora.get(id));
    }
}
