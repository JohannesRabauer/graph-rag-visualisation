package com.graphraglens.adapter.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.core.domain.Corpus;
import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.GraphExtraction;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.TextUnit;
import io.graphrag.core.port.LlmPort;
import io.graphrag.core.usecase.EntityTypes;
import io.graphrag.core.usecase.GraphElementMerger;
import io.graphrag.core.usecase.TextUnitSplitter;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.FinishReason;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    static final int MAX_EXTRACTION_OUTPUT_TOKENS = 4096;

    private final ChatModel jsonChatModel;
    private final ChatModel textChatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiLlmPort(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public OpenAiLlmPort(String apiKey, String modelName) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("OpenAI API key must not be blank");
        }
        String model = modelName == null || modelName.isBlank() ? DEFAULT_MODEL : modelName;
        // Two models are needed because OpenAI rejects response_format=json_object
        // unless the prompt itself contains the word "json" — the extraction
        // prompt does, but the plain-sentence community summary prompt does not.
        this.jsonChatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(60))
                .maxTokens(MAX_EXTRACTION_OUTPUT_TOKENS)
                .maxRetries(0)
                .responseFormat("json_object")
                .build();
        this.textChatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(0)
                .build();
    }

    OpenAiLlmPort(ChatModel jsonChatModel, ChatModel textChatModel) {
        this.jsonChatModel = jsonChatModel;
        this.textChatModel = textChatModel;
    }

    /**
     * Story 13.1: extracts the Corpus Text Unit by Text Unit (one OpenAI call
     * per unit, sequentially, against {@link EntityTypes#ALL}) and merges the
     * results. The ingestion pipeline does not use this — it drives
     * {@link #extract(TextUnit, List)} itself so it can persist and report
     * each unit — but it keeps the whole-Corpus entry point consistent.
     */
    @Override
    public GraphExtraction extract(Corpus corpus) {
        if (corpus == null || corpus.documents() == null || corpus.documents().isEmpty()) {
            return new GraphExtraction(List.of(), List.of());
        }

        Map<String, Entity> entitiesByIdentity = new LinkedHashMap<>();
        Map<String, Relationship> relationshipsByKey = new LinkedHashMap<>();
        for (TextUnit unit : TextUnitSplitter.split(corpus)) {
            GraphExtraction extraction = extract(unit, EntityTypes.ALL);
            for (Entity entity : extraction.entities()) {
                entitiesByIdentity.merge(entity.normalizedIdentity(), entity, GraphElementMerger::merge);
            }
            for (Relationship relationship : extraction.relationships()) {
                relationshipsByKey.merge(relationshipKey(relationship), relationship, GraphElementMerger::merge);
            }
        }
        return new GraphExtraction(new ArrayList<>(entitiesByIdentity.values()),
                new ArrayList<>(relationshipsByKey.values()));
    }

    /**
     * Story 13.1: one OpenAI extraction call over a single Text Unit,
     * restricted to {@code entityTypes}. No retries — a failure surfaces as
     * {@link LlmCallFailedException}.
     */
    @Override
    public GraphExtraction extract(TextUnit unit, List<String> entityTypes) {
        if (unit == null || unit.text() == null || unit.text().isBlank()) {
            return new GraphExtraction(List.of(), List.of());
        }

        ChatResponse response;
        try {
            response = jsonChatModel.chat(ChatRequest.builder()
                    .messages(UserMessage.from(extractionPrompt(unit, entityTypes)))
                    .maxOutputTokens(MAX_EXTRACTION_OUTPUT_TOKENS)
                    .build());
        } catch (RuntimeException e) {
            throw new LlmCallFailedException("OpenAI extraction call failed", e);
        }
        if (response != null && response.finishReason() == FinishReason.LENGTH) {
            throw new LlmCallFailedException("OpenAI extraction response hit the output-token limit ("
                    + MAX_EXTRACTION_OUTPUT_TOKENS + ")", null);
        }

        String text = response == null || response.aiMessage() == null ? "" : response.aiMessage().text();
        return parseExtraction(text);
    }

    /**
     * Builds the per-unit extraction prompt. Package-private so tests can
     * check it without a network call.
     */
    String extractionPrompt(TextUnit unit, List<String> entityTypes) {
        List<String> types = entityTypes == null || entityTypes.isEmpty() ? EntityTypes.ALL : entityTypes;
        String typeList = String.join(", ", types);
        return """
                You are a knowledge-graph extraction engine. Read the passage below and identify the \
                named entities and the relationships between them.

                Every entity type, and every relationship's sourceType and targetType, must be exactly \
                one of: %s. If none fits, use "%s".

                Respond with strict JSON only (no markdown, no commentary) using exactly this shape:
                {
                  "entities": [ { "name": "string", "type": "string", "description": "one or two sentences" } ],
                  "relationships": [ { "source": "string", "sourceType": "string", "type": "string", "target": "string", "targetType": "string", "description": "one or two sentences" } ]
                }

                Passage (from %s, passage %d):
                %s
                """.formatted(typeList, EntityTypes.CONCEPT, unit.documentName(), unit.ordinal() + 1, unit.text());
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
            String summary = textChatModel.chat(prompt);
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
                String description = node.path("description").asText("");
                if (name == null || name.isBlank()) {
                    continue;
                }
                Entity entity = new Entity(name, type == null || type.isBlank() ? "Concept" : type,
                        description, List.of());
                entitiesByIdentity.merge(entity.normalizedIdentity(), entity, GraphElementMerger::merge);
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
                String description = node.path("description").asText("");
                relationships.add(new Relationship(source, sourceType, type, target, targetType,
                        description, List.of(), 1));
            }
        }

        return new GraphExtraction(new ArrayList<>(entitiesByIdentity.values()), relationships);
    }

    private static String relationshipKey(Relationship relationship) {
        return Entity.identityOf(relationship.source(), relationship.sourceType())
                + "::" + relationship.type().toLowerCase(Locale.ROOT)
                + "::" + Entity.identityOf(relationship.target(), relationship.targetType());
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
