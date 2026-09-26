package com.graphraglens.web;

import com.graphraglens.core.domain.Corpus;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
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

    public enum CorpusWorkflowStatus {
        BUILDING,
        READY,
        FAILED
    }

    private final ConcurrentHashMap<String, Corpus> corpora = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CorpusWorkflowStatus> statuses = new ConcurrentHashMap<>();
    private final Set<String> offlineCorpusIds = ConcurrentHashMap.newKeySet();

    public void put(Corpus corpus) {
        corpora.put(corpus.id(), corpus);
        statuses.put(corpus.id(), CorpusWorkflowStatus.BUILDING);
    }

    /**
     * Marks a corpus as built entirely through the demo-safe offline path
     * (Story 9.1) — its own deterministic LLM/embedding stubs, never the
     * app's normally-configured ports, regardless of whether
     * {@code OPENAI_API_KEY} is set. Query-time blocks on this alone, so a
     * live call can never leak in through the query path either.
     */
    public void markOffline(String corpusId) {
        if (corpusId != null && !corpusId.isBlank()) {
            offlineCorpusIds.add(corpusId);
        }
    }

    public boolean isOffline(String corpusId) {
        return corpusId != null && offlineCorpusIds.contains(corpusId);
    }

    public Optional<Corpus> get(String id) {
        return Optional.ofNullable(corpora.get(id));
    }

    public int size() {
        return corpora.size();
    }

    public CorpusWorkflowStatus status(String corpusId) {
        return statuses.getOrDefault(corpusId, CorpusWorkflowStatus.BUILDING);
    }

    public void markReady(String corpusId) {
        statuses.put(corpusId, CorpusWorkflowStatus.READY);
    }

    public void markFailed(String corpusId) {
        statuses.put(corpusId, CorpusWorkflowStatus.FAILED);
    }
}
