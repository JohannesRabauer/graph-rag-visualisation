package io.graphrag.core.domain;

import java.util.List;

/**
 * A Corpus of uploaded documents queued for Knowledge Graph construction.
 *
 * <p>A Corpus is <em>not</em> a Neo4j node — its identity lives here, in an
 * in-memory {@code CorpusStore} owned by {@code graphrag-web}, while the
 * Entities/Relationships later extracted from it become the Neo4j source of
 * truth (Story 2.4+).
 *
 * @param id a generated identifier, unique per Corpus
 * @param documents the uploaded documents that make up this Corpus
 */
public record Corpus(String id, List<UploadedDocument> documents, String name) {

    public Corpus(String id, List<UploadedDocument> documents) {
        this(id, documents, defaultName(documents));
    }

    public List<String> documentNames() {
        return documents.stream().map(UploadedDocument::filename).toList();
    }

    public int documentCount() {
        return documents.size();
    }

    private static final int MAX_NAMED_FILES = 3;

    private static String defaultName(List<UploadedDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return "Untitled Corpus";
        }
        if (documents.size() == 1) {
            return documents.getFirst().filename();
        }
        List<String> filenames = documents.stream().map(UploadedDocument::filename).toList();
        if (filenames.size() <= MAX_NAMED_FILES) {
            return String.join(", ", filenames);
        }
        int remaining = filenames.size() - MAX_NAMED_FILES;
        String shown = String.join(", ", filenames.subList(0, MAX_NAMED_FILES));
        return shown + " +" + remaining + " more";
    }
}
