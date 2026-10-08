package dev.rabauer.graphrag.web.config;

import dev.rabauer.graphrag.adapter.langchain4j.LangChain4jEmbeddingPort;
import dev.rabauer.graphrag.adapter.langchain4j.LangChain4jLlmPort;
import dev.rabauer.graphrag.adapter.langchain4j.OpenAiEmbeddingPort;
import dev.rabauer.graphrag.adapter.langchain4j.OpenAiLlmPort;
import dev.rabauer.graphrag.adapter.neo4j.Neo4jCorpusRegistry;
import dev.rabauer.graphrag.adapter.neo4j.Neo4jGraphStoreAdapter;
import dev.rabauer.graphrag.adapter.neo4j.Neo4jVectorStoreAdapter;
import dev.rabauer.graphrag.adapter.parsing.PdfDocumentParserAdapter;
import dev.rabauer.graphrag.adapter.parsing.PlainTextDocumentParserAdapter;
import dev.rabauer.graphrag.core.llm.PromptedLlmPort;
import dev.rabauer.graphrag.core.port.DocumentParserPort;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.port.VectorStorePort;
import dev.rabauer.graphrag.core.usecase.AnswerVectorBaseline;
import dev.rabauer.graphrag.core.usecase.ConstructVectorIndex;
import dev.rabauer.graphrag.core.usecase.IngestCorpus;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
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
     *
     * <p>{@code GRAPHRAG_EXTRACTION_GLEANINGS} (default 0) sets how many extra
     * extraction turns ask the model for the Entities and Relationships it
     * missed; each turn is one more call per passage.
     * {@code GRAPHRAG_DESCRIPTION_SUMMARIES} (default false) has the model
     * summarise an Entity's or Relationship's description once it outgrows
     * its limit, instead of dropping what later passages add.
     */
    @Bean
    public LlmPort llmPort(@Value("${OPENAI_API_KEY:}") String openAiApiKey,
                           @Value("${OPENAI_MODEL:gpt-4o-mini}") String openAiModel,
                           @Value("${GRAPHRAG_EXTRACTION_GLEANINGS:0}") int gleanings,
                           @Value("${GRAPHRAG_DESCRIPTION_SUMMARIES:false}") boolean descriptionSummaries) {
        if (openAiApiKey != null && !openAiApiKey.isBlank()) {
            LOG.info("OPENAI_API_KEY detected — using real OpenAI-backed LLM adapter "
                    + "(model={}, gleanings={}, descriptionSummaries={})", openAiModel, gleanings, descriptionSummaries);
            return new OpenAiLlmPort(openAiApiKey, openAiModel, PromptedLlmPort.Options.defaults()
                    .withCorrectiveRetry(false).withGleanings(gleanings).withDescriptionSummaries(descriptionSummaries));
        }
        LOG.warn("OPENAI_API_KEY not set — falling back to the deterministic offline LLM stub. "
                + "Set OPENAI_API_KEY to enable real AI-driven graph extraction.");
        return new LangChain4jLlmPort();
    }

    /**
     * The single connection source of truth for Neo4j: built from
     * {@code NEO4J_URI}/{@code NEO4J_USERNAME}/{@code NEO4J_PASSWORD} (env
     * vars or {@code -D} system properties), with defaults matching
     * {@code docker-compose.yml}'s {@code neo4j} service (same host/port on
     * the compose network, same default credentials as its own
     * {@code NEO4J_AUTH}). Connectivity is not verified here — the driver is
     * lazy — {@link Neo4jConnectivityCheck} performs the fail-fast check once
     * the application is ready.
     */
    @Bean
    public Driver driver(@Value("${NEO4J_URI:bolt://neo4j:7687}") String neo4jUri,
                         @Value("${NEO4J_USERNAME:neo4j}") String neo4jUsername,
                         @Value("${NEO4J_PASSWORD:graphraglens}") String neo4jPassword) {
        return GraphDatabase.driver(neo4jUri, AuthTokens.basic(neo4jUsername, neo4jPassword));
    }

    @Bean
    public GraphStorePort graphStorePort(Driver driver) {
        return new Neo4jGraphStoreAdapter(driver);
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
    public VectorStorePort vectorStorePort(Driver driver) {
        return new Neo4jVectorStoreAdapter(driver);
    }

    /**
     * The durable replacement for the deleted {@code CorpusStore} (Story
     * 12.4). A plain adapter-side bean, not a {@code graphrag-core} port
     * (AD-19) — {@code CorpusController} calls it directly.
     */
    @Bean
    public Neo4jCorpusRegistry neo4jCorpusRegistry(Driver driver) {
        return new Neo4jCorpusRegistry(driver);
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
