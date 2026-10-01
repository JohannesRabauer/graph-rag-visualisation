package com.graphraglens.adapter.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphrag.core.domain.CommunitySummary;
import io.graphrag.core.domain.ContextItem;
import io.graphrag.core.domain.Corpus;
import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.GraphExtraction;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.RetrievalStep;
import io.graphrag.core.domain.SynthesizedAnswer;
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
    static final int MAX_SUMMARY_OUTPUT_TOKENS = 512;
    static final int MAX_PROMPT_DESCRIPTION_CHARS = 300;
    static final int MAX_ANSWER_OUTPUT_TOKENS = 1024;
    static final int MAX_PROMPT_CONTEXT_ITEM_CHARS = 1500;
    static final String NOT_IN_CONTEXT = SynthesizedAnswer.NOT_IN_CONTEXT;

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

    /**
     * Story 14.2: one JSON-mode OpenAI call that writes a Community's title and
     * 2-4 sentence summary from its members and internal Relationships (both
     * already capped by the caller). No retries; a failed call or a non-JSON
     * response surfaces as {@link LlmCallFailedException}. A blank summary
     * falls back to the deterministic one; the title is trimmed to six words.
     */
    @Override
    public CommunitySummary summarizeCommunity(Collection<Entity> members, Collection<Relationship> relationships) {
        if (members == null || members.isEmpty()) {
            return LlmPort.super.summarizeCommunity(members, relationships);
        }

        ChatResponse response;
        try {
            response = jsonChatModel.chat(ChatRequest.builder()
                    .messages(UserMessage.from(communitySummaryPrompt(members, relationships)))
                    .maxOutputTokens(MAX_SUMMARY_OUTPUT_TOKENS)
                    .build());
        } catch (RuntimeException e) {
            throw new LlmCallFailedException("OpenAI community summarization call failed", e);
        }
        if (response != null && response.finishReason() == FinishReason.LENGTH) {
            throw new LlmCallFailedException("OpenAI community summary response hit the output-token limit ("
                    + MAX_SUMMARY_OUTPUT_TOKENS + ")", null);
        }

        String text = response == null || response.aiMessage() == null ? "" : response.aiMessage().text();
        return parseCommunitySummary(text, members);
    }

    /**
     * Builds the community-summary prompt: numbered members and numbered
     * internal Relationships, each description truncated to
     * {@value #MAX_PROMPT_DESCRIPTION_CHARS} characters. Package-private so
     * tests can check it without a network call.
     */
    String communitySummaryPrompt(Collection<Entity> members, Collection<Relationship> relationships) {
        StringBuilder memberLines = new StringBuilder();
        int index = 1;
        for (Entity member : members) {
            if (member == null) {
                continue;
            }
            memberLines.append(index++).append(". ").append(member.name())
                    .append(" (").append(member.type()).append(")");
            String description = truncate(member.description());
            if (!description.isEmpty()) {
                memberLines.append(": ").append(description);
            }
            memberLines.append('\n');
        }

        StringBuilder relationshipLines = new StringBuilder();
        index = 1;
        if (relationships != null) {
            for (Relationship relationship : relationships) {
                if (relationship == null) {
                    continue;
                }
                relationshipLines.append(index++).append(". ").append(relationship.source())
                        .append(" -[").append(relationship.type()).append("]-> ").append(relationship.target());
                String description = truncate(relationship.description());
                if (!description.isEmpty()) {
                    relationshipLines.append(": ").append(description);
                }
                relationshipLines.append('\n');
            }
        }
        if (relationshipLines.isEmpty()) {
            relationshipLines.append("(none)\n");
        }

        return """
                You summarize one community of a knowledge graph. Using only the members and \
                relationships below, write a short title (at most 6 words) naming what the community \
                is about, and a summary of 2 to 4 sentences describing what connects its members.

                Respond with strict JSON only (no markdown, no commentary) using exactly this shape:
                { "title": "string", "summary": "string" }

                Members:
                %s
                Relationships:
                %s""".formatted(memberLines, relationshipLines);
    }

    CommunitySummary parseCommunitySummary(String response, Collection<Entity> members) {
        if (response == null || response.isBlank()) {
            throw new LlmCallFailedException("OpenAI community summary response was empty", null);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(stripMarkdownFences(response));
        } catch (Exception e) {
            throw new LlmCallFailedException("OpenAI community summary response was not valid JSON: " + response, e);
        }
        if (root == null || !root.isObject()) {
            throw new LlmCallFailedException("OpenAI community summary response was not a JSON object: " + response,
                    null);
        }
        JsonNode titleNode = root.path("title");
        JsonNode summaryNode = root.path("summary");
        String title = titleNode.isTextual() ? CommunitySummary.trimTitle(titleNode.asText()) : "";
        String summary = summaryNode.isTextual() ? summaryNode.asText().trim() : "";
        if (summary.isEmpty()) {
            summary = LlmPort.super.summarizeCommunity(members);
        }
        return new CommunitySummary(title, summary);
    }

    /** Story 15.2: this adapter really generates Local Search answers. */
    @Override
    public boolean synthesizesAnswers() {
        return true;
    }

    /**
     * Story 15.2: one JSON-mode OpenAI call that answers {@code question} from
     * the numbered context, citing items inline as {@code [n]}. No retries; a
     * failed call, a {@code length} finish or a non-JSON response surfaces as
     * {@link LlmCallFailedException}. Citations are resolved in core.
     */
    @Override
    public SynthesizedAnswer synthesizeAnswer(String question, List<ContextItem> context) {
        ChatResponse response;
        try {
            response = jsonChatModel.chat(ChatRequest.builder()
                    .messages(UserMessage.from(answerPrompt(question, context)))
                    .maxOutputTokens(MAX_ANSWER_OUTPUT_TOKENS)
                    .build());
        } catch (RuntimeException e) {
            throw new LlmCallFailedException("OpenAI answer synthesis call failed", e);
        }
        if (response != null && response.finishReason() == FinishReason.LENGTH) {
            throw new LlmCallFailedException("OpenAI answer response hit the output-token limit ("
                    + MAX_ANSWER_OUTPUT_TOKENS + ")", null);
        }

        String text = response == null || response.aiMessage() == null ? "" : response.aiMessage().text();
        return parseAnswer(text);
    }

    /**
     * Builds the answer prompt: every context item numbered {@code [1]..[n]}
     * in order, each text truncated to {@value #MAX_PROMPT_CONTEXT_ITEM_CHARS}
     * characters. Package-private so tests can check it without a network call.
     */
    String answerPrompt(String question, List<ContextItem> context) {
        StringBuilder items = new StringBuilder();
        if (context != null) {
            for (ContextItem item : context) {
                if (item == null) {
                    continue;
                }
                items.append('[').append(item.number()).append("] ").append(label(item.kind())).append(": ")
                        .append(truncate(item.text(), MAX_PROMPT_CONTEXT_ITEM_CHARS)).append('\n');
            }
        }
        if (items.isEmpty()) {
            items.append("(no context)\n");
        }

        return """
                You answer a question about a document collection using only the numbered context \
                below: entities and relationships from its knowledge graph, and source passages.

                Rules:
                - Use only facts stated in the context. Do not use outside knowledge.
                - Entity and Relationship items are background facts: use them, but never cite them.
                - Cite every claim inline only with the numbers of "Source passage" items that \
                support it, written as [n], for example [3] or [4][5]. Never put [n] on an Entity \
                or Relationship item.
                - If the context does not answer the question, set "answer" to "%s" and \
                "notInContext" to true.

                Respond with strict JSON only (no markdown, no commentary) using exactly this shape:
                { "answer": "string", "notInContext": false }

                Context:
                %s
                Question: %s
                """.formatted(NOT_IN_CONTEXT, items, question == null ? "" : question.trim());
    }

    SynthesizedAnswer parseAnswer(String response) {
        if (response == null || response.isBlank()) {
            throw new LlmCallFailedException("OpenAI answer response was empty", null);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(stripMarkdownFences(response));
        } catch (Exception e) {
            throw new LlmCallFailedException("OpenAI answer response was not valid JSON: " + response, e);
        }
        if (root == null || !root.isObject()) {
            throw new LlmCallFailedException("OpenAI answer response was not a JSON object: " + response, null);
        }
        JsonNode answerNode = root.path("answer");
        String answer = answerNode.isTextual() ? answerNode.asText().trim() : "";
        boolean notInContext = root.path("notInContext").asBoolean(false) || SynthesizedAnswer.isNotInContextSentinel(answer);
        return new SynthesizedAnswer(notInContext, notInContext ? "" : answer);
    }

    private static String label(RetrievalStep.Kind kind) {
        return switch (kind) {
            case ENTITY -> "Entity";
            case RELATIONSHIP -> "Relationship";
            case TEXT_UNIT -> "Source passage";
            default -> kind.name();
        };
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= MAX_PROMPT_DESCRIPTION_CHARS
                ? trimmed
                : trimmed.substring(0, MAX_PROMPT_DESCRIPTION_CHARS);
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
