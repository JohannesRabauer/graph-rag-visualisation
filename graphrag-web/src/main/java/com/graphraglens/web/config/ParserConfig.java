package com.graphraglens.web.config;

import com.graphraglens.adapter.langchain4j.LangChain4jLlmPort;
import com.graphraglens.adapter.neo4j.InMemoryGraphStoreAdapter;
import com.graphraglens.adapter.parsing.PdfDocumentParserAdapter;
import com.graphraglens.adapter.parsing.PlainTextDocumentParserAdapter;
import com.graphraglens.core.port.DocumentParserPort;
import com.graphraglens.core.port.GraphStorePort;
import com.graphraglens.core.port.LlmPort;
import com.graphraglens.core.usecase.IngestCorpus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires the framework-free parsing and knowledge-graph adapters into Spring.
 */
@Configuration
public class ParserConfig {

    @Bean
    public DocumentParserPort plainTextDocumentParserAdapter() {
        return new PlainTextDocumentParserAdapter();
    }

    @Bean
    public DocumentParserPort pdfDocumentParserAdapter() {
        return new PdfDocumentParserAdapter();
    }

    @Bean
    public LlmPort llmPort() {
        return new LangChain4jLlmPort();
    }

    @Bean
    public GraphStorePort graphStorePort() {
        return new InMemoryGraphStoreAdapter();
    }

    @Bean
    public IngestCorpus ingestCorpus(List<DocumentParserPort> documentParsers) {
        return new IngestCorpus(documentParsers);
    }
}
