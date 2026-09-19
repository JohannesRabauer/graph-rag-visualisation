package com.graphraglens.adapter.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphraglens.core.domain.ExtractedEntity;
import com.graphraglens.core.domain.ExtractedRelationship;
import com.graphraglens.core.domain.ExtractionResult;
import com.graphraglens.core.port.LlmPort;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.util.ArrayList;
import java.util.List;

/**
 * LangChain4j-based live LLM extraction adapter.
 */
public class Langchain4jLlmAdapter implements LlmPort {

    private static final String EXTRACTION_PROMPT = """
            Extract entities and relationships from the text.
            Return strictly valid JSON in this exact shape:
            {
              "entities": [{"name":"...", "type":"..."}],
              "relationships": [{
                "sourceName":"...", "sourceType":"...",
                "targetName":"...", "targetType":"...",
                "type":"..."
              }]
            }
            Do not include markdown fences or any non-JSON text.

            Text:
            %s
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public Langchain4jLlmAdapter(String apiKey) {
        this(OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName("gpt-4o-mini")
                .build());
    }

    Langchain4jLlmAdapter(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public ExtractionResult extractEntitiesAndRelationships(String text) {
        try {
            String response = chatModel.chat(String.format(EXTRACTION_PROMPT, text));
            JsonNode root = objectMapper.readTree(response);
            List<ExtractedEntity> entities = parseEntities(root.path("entities"));
            List<ExtractedRelationship> relationships = parseRelationships(root.path("relationships"));
            return new ExtractionResult(entities, relationships);
        } catch (Exception e) {
            throw new RuntimeException("LLM extraction failed", e);
        }
    }

    private List<ExtractedEntity> parseEntities(JsonNode nodes) {
        List<ExtractedEntity> entities = new ArrayList<>();
        if (!nodes.isArray()) {
            return entities;
        }
        for (JsonNode node : nodes) {
            String name = node.path("name").asText("").trim();
            String type = node.path("type").asText("Unknown").trim();
            if (!name.isBlank()) {
                entities.add(new ExtractedEntity(name, type.isBlank() ? "Unknown" : type));
            }
        }
        return entities;
    }

    private List<ExtractedRelationship> parseRelationships(JsonNode nodes) {
        List<ExtractedRelationship> relationships = new ArrayList<>();
        if (!nodes.isArray()) {
            return relationships;
        }
        for (JsonNode node : nodes) {
            String sourceName = node.path("sourceName").asText("").trim();
            String sourceType = node.path("sourceType").asText("Unknown").trim();
            String targetName = node.path("targetName").asText("").trim();
            String targetType = node.path("targetType").asText("Unknown").trim();
            String type = node.path("type").asText("RELATED_TO").trim();
            if (!sourceName.isBlank() && !targetName.isBlank()) {
                relationships.add(new ExtractedRelationship(
                        new ExtractedEntity(sourceName, sourceType.isBlank() ? "Unknown" : sourceType),
                        new ExtractedEntity(targetName, targetType.isBlank() ? "Unknown" : targetType),
                        type.isBlank() ? "RELATED_TO" : type));
            }
        }
        return relationships;
    }
}
