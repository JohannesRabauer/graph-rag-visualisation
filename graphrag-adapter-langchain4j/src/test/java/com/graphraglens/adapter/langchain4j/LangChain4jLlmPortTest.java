package com.graphraglens.adapter.langchain4j;

import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.UploadedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChain4jLlmPortTest {

    @Test
    void extractsEntitiesAndRelationshipsFromCorpusText() {
        LangChain4jLlmPort port = new LangChain4jLlmPort();
        Corpus corpus = new Corpus("c1", List.of(new UploadedDocument("sherlock.txt", "Sherlock Holmes met Dr. Watson. Mary Morstan helped Sherlock Holmes.")));

        var extraction = port.extract(corpus);

        assertTrue(extraction.entities().size() >= 3);
        assertTrue(extraction.relationships().stream().anyMatch(r -> r.type().equals("met") || r.type().equals("related_to") || r.type().equals("helped")));
    }
}
