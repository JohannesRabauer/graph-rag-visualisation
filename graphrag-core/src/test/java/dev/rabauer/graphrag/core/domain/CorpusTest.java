package dev.rabauer.graphrag.core.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CorpusTest {

    @Test
    void defaultNameForNoDocumentsIsUntitledCorpus() {
        Corpus corpus = new Corpus("id-1", List.of());
        assertEquals("Untitled Corpus", corpus.name());
    }

    @Test
    void defaultNameForSingleDocumentIsItsFilename() {
        Corpus corpus = new Corpus("id-1", List.of(new UploadedDocument("notes.txt", "content")));
        assertEquals("notes.txt", corpus.name());
    }

    @Test
    void defaultNameForUpToThreeDocumentsJoinsAllFilenames() {
        Corpus corpus = new Corpus("id-1", List.of(
                new UploadedDocument("a.txt", "content"),
                new UploadedDocument("b.txt", "content"),
                new UploadedDocument("c.txt", "content")));
        assertEquals("a.txt, b.txt, c.txt", corpus.name());
    }

    @Test
    void defaultNameForMoreThanThreeDocumentsTruncatesWithCount() {
        Corpus corpus = new Corpus("id-1", List.of(
                new UploadedDocument("a.txt", "content"),
                new UploadedDocument("b.txt", "content"),
                new UploadedDocument("c.txt", "content"),
                new UploadedDocument("d.txt", "content"),
                new UploadedDocument("e.txt", "content")));
        assertEquals("a.txt, b.txt, c.txt +2 more", corpus.name());
    }
}
