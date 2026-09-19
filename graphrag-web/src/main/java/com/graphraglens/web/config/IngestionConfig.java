package com.graphraglens.web.config;

import com.graphraglens.adapter.langchain4j.Langchain4jLlmAdapter;
import com.graphraglens.adapter.neo4j.Neo4jGraphStoreAdapter;
import com.graphraglens.core.port.GraphStorePort;
import com.graphraglens.core.port.LlmPort;
import com.graphraglens.core.usecase.ConstructKnowledgeGraph;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires Epic 2 ingestion/extraction ports and use cases.
 */
@Configuration
public class IngestionConfig {

    @Bean
    public LlmPort llmPort(@Value("${OPENAI_API_KEY:}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return text -> {
                throw new IllegalStateException("OPENAI_API_KEY is required for live extraction.");
            };
        }
        return new Langchain4jLlmAdapter(apiKey);
    }

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver(
            @Value("${NEO4J_URI:bolt://neo4j:7687}") String uri,
            @Value("${NEO4J_USER:neo4j}") String user,
            @Value("${NEO4J_PASSWORD:graphraglens}") String password) {
        return GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    }

    @Bean
    public GraphStorePort graphStorePort(Driver neo4jDriver) {
        return new Neo4jGraphStoreAdapter(neo4jDriver);
    }

    @Bean
    public ConstructKnowledgeGraph constructKnowledgeGraph(LlmPort llmPort, GraphStorePort graphStorePort) {
        return new ConstructKnowledgeGraph(llmPort, graphStorePort);
    }
}
