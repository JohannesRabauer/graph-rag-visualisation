package com.graphraglens.web.config;

import com.graphraglens.adapter.langchain4j.LangChain4jEmbeddingPort;
import com.graphraglens.adapter.langchain4j.LangChain4jLlmPort;
import com.graphraglens.adapter.langchain4j.OpenAiEmbeddingPort;
import com.graphraglens.adapter.langchain4j.OpenAiLlmPort;
import com.graphraglens.adapter.neo4j.InMemoryGraphStoreAdapter;
import com.graphraglens.adapter.neo4j.InMemoryVectorStoreAdapter;
import com.graphraglens.adapter.parsing.PdfDocumentParserAdapter;
import com.graphraglens.adapter.parsing.PlainTextDocumentParserAdapter;
import com.graphraglens.core.port.DocumentParserPort;
import com.graphraglens.core.port.EmbeddingPort;
import com.graphraglens.core.port.GraphStorePort;
import com.graphraglens.core.port.LlmPort;
import com.graphraglens.core.port.VectorStorePort;
import com.graphraglens.core.usecase.AnswerVectorBaseline;
import com.graphraglens.core.usecase.ConstructVectorIndex;
import com.graphraglens.core.usecase.IngestCorpus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires the framework-free parsing and knowledge-graph adapters into Spring.
 */
@Configuration
public class ParserConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ParserConfig.class);

    @Bean
    public DocumentParserPort plainTextDocumentParserAdapter() {
        return new PlainTextDocumentParserAdapter();
    }

    @Bean
    public DocumentParserPort pdfDocumentParserAdapter() {
        return new PdfDocumentParserAdapter();
    }

    /**
     * Selects the {@link LlmPort} implementation based on whether a real
     * OpenAI API key is available in the environment. When
     * {@code OPENAI_API_KEY} is set (env var or {@code -D} system property,
     * both resolved by Spring's {@code ${OPENAI_API_KEY:}} placeholder),
     * knowledge-graph extraction and community summarization make real calls
     * to OpenAI via LangChain4j. Otherwise the deterministic, offline stub
     * is used so local development, CI, and tests keep working without any
     * network access or credentials — matching the architecture's
     * environment-variable-only configuration rule (no in-app config UI).
     */
    @Bean
    public LlmPort llmPort(@Value("${OPENAI_API_KEY:}") String openAiApiKey,
                           @Value("${OPENAI_MODEL:gpt-4o-mini}") String openAiModel) {
        if (openAiApiKey != null && !openAiApiKey.isBlank()) {
            LOG.info("OPENAI_API_KEY detected — using real OpenAI-backed LLM adapter (model={})", openAiModel);
            return new OpenAiLlmPort(openAiApiKey, openAiModel);
        }
        LOG.warn("OPENAI_API_KEY not set — falling back to the deterministic offline LLM stub. "
                + "Set OPENAI_API_KEY to enable real AI-driven graph extraction.");
        return new LangChain4jLlmPort();
    }

    @Bean
    public GraphStorePort graphStorePort() {
        return new InMemoryGraphStoreAdapter();
    }

    @Bean
    public EmbeddingPort embeddingPort(@Value("${OPENAI_API_KEY:}") String openAiApiKey,
                                       @Value("${OPENAI_EMBEDDING_MODEL:text-embedding-3-small}") String openAiEmbeddingModel) {
        if (openAiApiKey != null && !openAiApiKey.isBlank()) {
            LOG.info("OPENAI_API_KEY detected — using real OpenAI-backed embedding adapter (model={})", openAiEmbeddingModel);
            return new OpenAiEmbeddingPort(openAiApiKey, openAiEmbeddingModel);
        }
        LOG.warn("OPENAI_API_KEY not set — falling back to the deterministic offline embedding stub. "
                + "Set OPENAI_API_KEY to enable real AI-driven embeddings.");
        return new LangChain4jEmbeddingPort();
    }

    @Bean
    public VectorStorePort vectorStorePort() {
        return new InMemoryVectorStoreAdapter();
    }

    @Bean
    public ConstructVectorIndex constructVectorIndex(EmbeddingPort embeddingPort, VectorStorePort vectorStorePort) {
        return new ConstructVectorIndex(embeddingPort, vectorStorePort);
    }

    @Bean
    public AnswerVectorBaseline answerVectorBaseline(EmbeddingPort embeddingPort, VectorStorePort vectorStorePort,
                                                     LlmPort llmPort) {
        return new AnswerVectorBaseline(embeddingPort, vectorStorePort, llmPort);
    }

    @Bean
    public IngestCorpus ingestCorpus(List<DocumentParserPort> documentParsers) {
        return new IngestCorpus(documentParsers);
    }
}
