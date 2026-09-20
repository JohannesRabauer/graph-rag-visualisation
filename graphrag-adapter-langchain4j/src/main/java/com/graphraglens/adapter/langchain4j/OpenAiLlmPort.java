package com.graphraglens.adapter.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.GraphExtraction;
import com.graphraglens.core.domain.Relationship;
import com.graphraglens.core.domain.UploadedDocument;
import com.graphraglens.core.port.LlmPort;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real, network-backed {@link LlmPort} implementation that calls the OpenAI
 * Chat Completions API (via LangChain4j) to perform knowledge-graph
 * extraction and community summarization.
 *
 * <p>Per the architecture's hard rule (AD-3), only this adapter module may
 * reference LangChain4j/OpenAI types; {@code graphrag-core} and every other
 * module only ever see the framework-free {@link LlmPort} interface.
 *
 * <p>Failures are never retried automatically ({@code maxRetries(0)}) so
 * they surface visibly to the caller instead of silently masking a broken
 * or misconfigured integration.
 */
public class OpenAiLlmPort implements LlmPort {

    private static final String DEFAULT_MODEL = "gpt-4o-mini";

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiLlmPort(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public OpenAiLlmPort(String apiKey, String modelName) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("OpenAI API key must not be blank");
        }
        this.chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName == null || modelName.isBlank() ? DEFAULT_MODEL : modelName)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(0)
                .responseFormat("json_object")
                .build();
    }

    @Override
    public GraphExtraction extract(Corpus corpus) {
        if (corpus == null || corpus.documents() == null || corpus.documents().isEmpty()) {
            return new GraphExtraction(List.of(), List.of());
        }

        String documentsText = corpus.documents().stream()
                .filter(document -> document != null && document.content() != null && !document.content().isBlank())
                .map(UploadedDocument::content)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");

        if (documentsText.isBlank()) {
            return new GraphExtraction(List.of(), List.of());
        }

        String prompt = """
                You are a knowledge-graph extraction engine. Read the text below and identify the \
                named entities (people, places, organizations, concepts) and the relationships between them.

                Respond with strict JSON only (no markdown, no commentary) using exactly this shape:
                {
                  "entities": [ { "name": "string", "type": "string" } ],
                  "relationships": [ { "source": "string", "sourceType": "string", "type": "string", "target": "string", "targetType": "string" } ]
                }

                Text:
                %s
                """.formatted(documentsText);

        String response;
        try {
            response = chatModel.chat(prompt);
        } catch (RuntimeException e) {
            throw new LlmCallFailedException("OpenAI extraction call failed", e);
        }

        return parseExtraction(response);
    }

    @Override
    public String summarizeCommunity(Collection<Entity> members) {
        if (members == null || members.isEmpty()) {
            return "A small connected cluster of related entities.";
        }

        String names = members.stream()
                .map(Entity::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");

        if (names.isBlank()) {
            return "A small connected cluster of related entities.";
        }

        String prompt = """
                Write one concise sentence (no more than 30 words) summarizing what connects \
                the following entities in a knowledge graph community: %s
                """.formatted(names);

        try {
            String summary = chatModel.chat(prompt);
            return summary == null || summary.isBlank()
                    ? "This community centers on " + names + "."
                    : summary.trim();
        } catch (RuntimeException e) {
            throw new LlmCallFailedException("OpenAI community summarization call failed", e);
        }
    }

    GraphExtraction parseExtraction(String response) {
        if (response == null || response.isBlank()) {
            return new GraphExtraction(List.of(), List.of());
        }

        String json = stripMarkdownFences(response);

        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new LlmCallFailedException("OpenAI response was not valid JSON: " + response, e);
        }

        Map<String, Entity> entitiesByIdentity = new LinkedHashMap<>();
        JsonNode entityNodes = root.path("entities");
        if (entityNodes.isArray()) {
            for (JsonNode node : entityNodes) {
                String name = node.path("name").asText(null);
                String type = node.path("type").asText(null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                Entity entity = new Entity(name, type == null || type.isBlank() ? "Concept" : type);
                entitiesByIdentity.putIfAbsent(entity.normalizedIdentity(), entity);
            }
        }

        List<Relationship> relationships = new ArrayList<>();
        JsonNode relationshipNodes = root.path("relationships");
        if (relationshipNodes.isArray()) {
            for (JsonNode node : relationshipNodes) {
                String source = node.path("source").asText(null);
                String target = node.path("target").asText(null);
                if (source == null || source.isBlank() || target == null || target.isBlank()) {
                    continue;
                }
                String sourceType = node.path("sourceType").asText("Concept");
                String targetType = node.path("targetType").asText("Concept");
                String type = node.path("type").asText("related_to");
                relationships.add(new Relationship(source, sourceType, type, target, targetType));
            }
        }

        return new GraphExtraction(new ArrayList<>(entitiesByIdentity.values()), relationships);
    }

    private String stripMarkdownFences(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence != -1) {
                trimmed = trimmed.substring(0, lastFence);
            }
        }
        return trimmed.trim();
    }

    /**
     * Signals that a call to the OpenAI API failed (network error, API
     * error, or an unparseable response). Deliberately unchecked and never
     * retried automatically — callers (see {@code CorpusController}) surface
     * this to the user instead of silently masking it.
     */
    public static class LlmCallFailedException extends RuntimeException {
        public LlmCallFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
