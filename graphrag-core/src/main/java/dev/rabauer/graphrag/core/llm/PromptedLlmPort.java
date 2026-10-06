package dev.rabauer.graphrag.core.llm;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunitySummary;
import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.SynthesizedAnswer;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.usecase.EntityTypes;
import dev.rabauer.graphrag.core.usecase.GraphElementMerger;
import dev.rabauer.graphrag.core.usecase.TextUnitSplitter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * An {@link LlmPort} whose prompts, JSON Schemas and reply parsing live in
 * core, built for small local models (for example Ollama llama3.2): an
 * application implements only {@link #complete(CompletionRequest)} on its own
 * client (Spring AI, LangChain4j, plain HTTP).
 *
 * <p>Every call asks for one JSON object and passes its JSON Schema in
 * {@link CompletionRequest#jsonSchema()}, so a client that supports
 * schema-constrained output (Ollama {@code format}, OpenAI
 * {@code response_format: json_schema}) can enforce it; the prompt also shows
 * the shape. Replies are parsed with {@link LenientJson} — prose and markdown
 * around the object, trailing commas, single quotes and JSON cut off by the
 * token limit are tolerated. When a reply is still unusable (no object, a
 * required field missing or empty), the call is asked once more with the bad
 * reply and a corrective message ({@link Options#correctiveRetry()}); if that
 * fails too, an {@link LlmReplyException} is thrown for that item. A failure
 * of {@link #complete} itself propagates unchanged and is never retried.
 *
 * <p>Capabilities: Community summaries and DRIFT sub-questions are always
 * model-backed; extraction and answer synthesis are switched by
 * {@link Options#extraction()} and {@link Options#synthesis()} (both off by
 * default: an exact, imported graph needs neither).
 */
public abstract class PromptedLlmPort implements LlmPort {

    static final int MAX_DESCRIPTION_CHARS = 300;
    static final int MAX_CONTEXT_ITEM_CHARS = 1500;

    private final Options options;

    protected PromptedLlmPort() {
        this(Options.defaults());
    }

    protected PromptedLlmPort(Options options) {
        this.options = options == null ? Options.defaults() : options;
    }

    /**
     * Sends {@code request} to the model and returns the raw reply text.
     * Throwing here propagates unchanged (no retry).
     */
    protected abstract String complete(CompletionRequest request);

    // -- Capabilities -------------------------------------------------------------------

    @Override
    public boolean extractsEntities() {
        return options.extraction();
    }

    @Override
    public boolean summarizesCommunities() {
        return true;
    }

    @Override
    public boolean derivesSubQuestions() {
        return true;
    }

    @Override
    public boolean synthesizesAnswers() {
        return options.synthesis();
    }

    // -- Extraction -----------------------------------------------------------------------

    /** Extracts Text Unit by Text Unit when extraction is on; otherwise {@link GraphExtraction#empty()}. */
    @Override
    public GraphExtraction extract(Corpus corpus) {
        if (!options.extraction() || corpus == null || corpus.documents() == null) {
            return GraphExtraction.empty();
        }
        Map<String, Entity> entities = new LinkedHashMap<>();
        Map<String, Relationship> relationships = new LinkedHashMap<>();
        for (TextUnit unit : TextUnitSplitter.split(corpus)) {
            GraphExtraction extraction = extract(unit, EntityTypes.ALL);
            extraction.entities().forEach(entity -> entities.merge(entity.normalizedIdentity(), entity,
                    GraphElementMerger::merge));
            extraction.relationships().forEach(relationship -> relationships.merge(relationship.sourceIdentity() + "|"
                    + relationship.type() + "|" + relationship.targetIdentity(), relationship, GraphElementMerger::merge));
        }
        return new GraphExtraction(List.copyOf(entities.values()), List.copyOf(relationships.values()));
    }

    @Override
    public GraphExtraction extract(TextUnit unit, List<String> entityTypes) {
        if (!options.extraction() || unit == null || unit.text() == null || unit.text().isBlank()) {
            return GraphExtraction.empty();
        }
        List<String> types = entityTypes == null || entityTypes.isEmpty() ? EntityTypes.ALL : entityTypes;
        return call(Purpose.EXTRACTION, extractionPrompt(unit, types), Schemas.extraction(types),
                options.extractionTokens(), object -> parseExtraction(object, types));
    }

    String extractionPrompt(TextUnit unit, List<String> types) {
        return """
                Extract the named entities and the relationships between them from the passage.
                Entity types: %s. Use "%s" when no other type fits.
                Reply with one JSON object and nothing else, shaped like:
                {"entities":[{"name":"...","type":"...","description":"one sentence"}],\
                "relationships":[{"source":"...","sourceType":"...","type":"verb_phrase","target":"...",\
                "targetType":"...","description":"one sentence"}]}

                Passage (%s, part %d):
                %s""".formatted(String.join(", ", types), types.getLast(), unit.documentName(), unit.ordinal() + 1,
                unit.text());
    }

    private static Optional<GraphExtraction> parseExtraction(Map<String, Object> object, List<String> types) {
        if (!object.containsKey("entities") && !object.containsKey("relationships")) {
            return Optional.empty();
        }
        List<Entity> entities = new ArrayList<>();
        for (Map<String, Object> node : LenientJson.objects(object, "entities")) {
            String name = LenientJson.string(node, "name");
            if (!name.isEmpty()) {
                entities.add(new Entity(name, EntityTypes.normalize(LenientJson.string(node, "type"), types),
                        LenientJson.string(node, "description"), List.of()));
            }
        }
        List<Relationship> relationships = new ArrayList<>();
        for (Map<String, Object> node : LenientJson.objects(object, "relationships")) {
            String source = LenientJson.string(node, "source");
            String target = LenientJson.string(node, "target");
            if (!source.isEmpty() && !target.isEmpty()) {
                String type = LenientJson.string(node, "type");
                relationships.add(new Relationship(source,
                        EntityTypes.normalize(LenientJson.string(node, "sourceType"), types),
                        type.isEmpty() ? "related_to" : type, target,
                        EntityTypes.normalize(LenientJson.string(node, "targetType"), types),
                        LenientJson.string(node, "description"), List.of(), 1));
            }
        }
        return Optional.of(new GraphExtraction(entities, relationships));
    }

    // -- Community summaries ---------------------------------------------------------------

    @Override
    public CommunitySummary summarizeCommunity(Collection<Entity> members, Collection<Relationship> relationships) {
        if (members == null || members.isEmpty()) {
            return LlmPort.super.summarizeCommunity(members, relationships);
        }
        return call(Purpose.COMMUNITY_SUMMARY, communitySummaryPrompt(members, relationships),
                Schemas.COMMUNITY_SUMMARY, options.summaryTokens(), PromptedLlmPort::parseSummary);
    }

    String communitySummaryPrompt(Collection<Entity> members, Collection<Relationship> relationships) {
        StringBuilder lines = new StringBuilder("Members:\n");
        int index = 1;
        for (Entity member : members) {
            lines.append(index++).append(". ").append(member.name()).append(" (").append(member.type()).append(')');
            String description = truncate(member.description(), MAX_DESCRIPTION_CHARS);
            if (!description.isEmpty()) {
                lines.append(": ").append(description);
            }
            lines.append('\n');
        }
        lines.append("Relationships:\n");
        index = 1;
        for (Relationship relationship : relationships == null ? List.<Relationship>of() : relationships) {
            lines.append(index++).append(". ").append(relationship.source()).append(" -[")
                    .append(relationship.type()).append("]-> ").append(relationship.target()).append('\n');
        }
        if (index == 1) {
            lines.append("(none)\n");
        }
        return """
                Summarize this group of connected elements. Use only the members and relationships below.
                Reply with one JSON object and nothing else, shaped like:
                {"title":"at most 6 words","summary":"2 to 4 sentences on what connects the members"}

                %s""".formatted(lines);
    }

    private static Optional<CommunitySummary> parseSummary(Map<String, Object> object) {
        String summary = LenientJson.string(object, "summary");
        if (summary.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CommunitySummary(CommunitySummary.trimTitle(LenientJson.string(object, "title")),
                summary));
    }

    // -- DRIFT sub-questions ----------------------------------------------------------------

    /**
     * One sub-question per candidate Community, in candidate order. Missing
     * ones (a small model returning fewer) are filled with the deterministic
     * default; extra ones are dropped.
     */
    @Override
    public List<String> deriveDriftSubQuestions(String question, Collection<Community> communities) {
        if (communities == null || communities.isEmpty()) {
            return List.of();
        }
        List<Community> candidates = communities.stream().filter(Objects::nonNull).toList();
        List<String> derived = call(Purpose.SUB_QUESTIONS, subQuestionPrompt(question, candidates),
                Schemas.SUB_QUESTIONS, options.subQuestionTokens(), object -> {
                    List<String> subQuestions = LenientJson.strings(object, "subQuestions");
                    return subQuestions.isEmpty() ? Optional.empty() : Optional.of(subQuestions);
                });
        List<String> fallback = LlmPort.super.deriveDriftSubQuestions(question, candidates);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            result.add(i < derived.size() ? derived.get(i) : fallback.get(i));
        }
        return List.copyOf(result);
    }

    String subQuestionPrompt(String question, List<Community> communities) {
        StringBuilder lines = new StringBuilder();
        int index = 1;
        for (Community community : communities) {
            lines.append(index++).append(". ").append(community.title().isEmpty() ? "" : community.title() + ": ")
                    .append(truncate(community.summary(), MAX_DESCRIPTION_CHARS)).append('\n');
        }
        return """
                Break the question into one focused follow-up question per group below, in the same order \
                (%d questions). Keep names and identifiers from the question exactly as written.
                Reply with one JSON object and nothing else, shaped like:
                {"subQuestions":["...","..."]}

                Question: %s
                Groups:
                %s""".formatted(communities.size(), question == null ? "" : question.trim(), lines);
    }

    // -- Answer synthesis ------------------------------------------------------------------

    @Override
    public SynthesizedAnswer synthesizeAnswer(String question, List<ContextItem> context) {
        if (!options.synthesis()) {
            return null;
        }
        return call(Purpose.ANSWER, answerPrompt(question, context), Schemas.ANSWER, options.answerTokens(),
                object -> {
                    String answer = LenientJson.string(object, "answer");
                    boolean notInContext = Boolean.TRUE.equals(object.get("notInContext"))
                            || SynthesizedAnswer.isNotInContextSentinel(answer);
                    if (!notInContext && answer.isEmpty()) {
                        return Optional.empty();
                    }
                    return Optional.of(new SynthesizedAnswer(notInContext, notInContext ? "" : answer));
                });
    }

    String answerPrompt(String question, List<ContextItem> context) {
        StringBuilder items = new StringBuilder();
        for (ContextItem item : context == null ? List.<ContextItem>of() : context) {
            items.append('[').append(item.number()).append("] ").append(label(item.kind())).append(": ")
                    .append(truncate(item.text(), MAX_CONTEXT_ITEM_CHARS)).append('\n');
        }
        return """
                Answer the question using only the numbered context. Cite source passages inline as [n]; \
                only "Source passage" items may be cited. If the context does not answer the question, \
                set "answer" to "%s" and "notInContext" to true.
                Reply with one JSON object and nothing else, shaped like:
                {"answer":"...","notInContext":false}

                Context:
                %s
                Question: %s""".formatted(SynthesizedAnswer.NOT_IN_CONTEXT,
                items.isEmpty() ? "(no context)\n" : items, question == null ? "" : question.trim());
    }

    // -- The call ---------------------------------------------------------------------------

    /**
     * Asks once, parses leniently, asks once more with a corrective message
     * when the reply is unusable, then gives up with {@link LlmReplyException}.
     */
    private <T> T call(Purpose purpose, String prompt, String schema, int maxTokens,
                       Function<Map<String, Object>, Optional<T>> parse) {
        List<Message> messages = new ArrayList<>(List.of(new Message(Role.USER, prompt)));
        String reply = "";
        int attempts = options.correctiveRetry() ? 2 : 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            reply = complete(new CompletionRequest(purpose, List.copyOf(messages), schema, maxTokens));
            Optional<LenientJson.Result> parsed = LenientJson.parseObject(reply);
            Optional<T> value = parsed.flatMap(result -> parse.apply(result.object()));
            if (value.isPresent()) {
                return value.get();
            }
            messages.add(new Message(Role.ASSISTANT, reply == null ? "" : reply));
            messages.add(new Message(Role.USER, (parsed.isEmpty()
                    ? "Your reply did not contain a JSON object."
                    : "Your JSON object was missing required fields.")
                    + " Reply again with only one JSON object matching this JSON Schema, no prose, no markdown:\n"
                    + schema));
        }
        throw new LlmReplyException(purpose, attempts, reply, "The model's " + purpose.name().toLowerCase()
                + " reply could not be used after " + attempts + " attempt(s): " + abbreviate(reply), null);
    }

    private static String label(RetrievalStep.Kind kind) {
        return switch (kind) {
            case ENTITY -> "Entity";
            case RELATIONSHIP -> "Relationship";
            case TEXT_UNIT -> "Source passage";
            case COMMUNITY -> "Community summary";
            default -> kind.name();
        };
    }

    private static String truncate(String text, int maxChars) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars);
    }

    private static String abbreviate(String reply) {
        String flat = reply == null ? "" : reply.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    // -- Types ------------------------------------------------------------------------------

    /** What a completion is for. */
    public enum Purpose {
        EXTRACTION,
        COMMUNITY_SUMMARY,
        SUB_QUESTIONS,
        ANSWER
    }

    /** Who said a message. */
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT
    }

    /** One message of the conversation sent to the model. */
    public record Message(Role role, String text) {
        public Message {
            Objects.requireNonNull(role, "role");
            text = text == null ? "" : text;
        }
    }

    /**
     * One completion to run.
     *
     * @param purpose         what it is for
     * @param messages        the conversation: the prompt and, on the corrective
     *                        retry, the bad reply and the correction
     * @param jsonSchema      the JSON Schema (draft 2020-12, as text) the reply
     *                        must match; pass it to the model's structured-output
     *                        option when the client supports one
     * @param maxOutputTokens a suggested output-token limit
     */
    public record CompletionRequest(Purpose purpose, List<Message> messages, String jsonSchema, int maxOutputTokens) {
        public CompletionRequest {
            Objects.requireNonNull(purpose, "purpose");
            messages = messages == null ? List.of() : List.copyOf(messages);
            jsonSchema = jsonSchema == null ? "" : jsonSchema;
        }

        /** The last user message: the prompt on the first attempt, the correction on the retry. */
        public String lastUserText() {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).role() == Role.USER) {
                    return messages.get(i).text();
                }
            }
            return "";
        }
    }

    /**
     * @param extraction        whether {@link #extract(TextUnit, List)} calls the model
     * @param synthesis         whether {@link #synthesizeAnswer(String, List)} calls the model
     * @param correctiveRetry   whether an unusable reply is asked once more
     * @param extractionTokens  output-token limit suggested for extraction
     * @param summaryTokens     output-token limit suggested for Community summaries
     * @param subQuestionTokens output-token limit suggested for DRIFT sub-questions
     * @param answerTokens      output-token limit suggested for answers
     */
    public record Options(boolean extraction, boolean synthesis, boolean correctiveRetry, int extractionTokens,
                          int summaryTokens, int subQuestionTokens, int answerTokens) {

        /** Summaries and sub-questions only, with the corrective retry. */
        public static Options defaults() {
            return new Options(false, false, true, 4096, 512, 512, 1024);
        }

        public Options withExtraction(boolean value) {
            return new Options(value, synthesis, correctiveRetry, extractionTokens, summaryTokens, subQuestionTokens,
                    answerTokens);
        }

        public Options withSynthesis(boolean value) {
            return new Options(extraction, value, correctiveRetry, extractionTokens, summaryTokens, subQuestionTokens,
                    answerTokens);
        }

        public Options withCorrectiveRetry(boolean value) {
            return new Options(extraction, synthesis, value, extractionTokens, summaryTokens, subQuestionTokens,
                    answerTokens);
        }
    }

    /** The JSON Schemas of the replies. */
    public static final class Schemas {

        public static final String COMMUNITY_SUMMARY = """
                {"type":"object","properties":{"title":{"type":"string"},"summary":{"type":"string"}},\
                "required":["title","summary"]}""";

        public static final String SUB_QUESTIONS = """
                {"type":"object","properties":{"subQuestions":{"type":"array","items":{"type":"string"}}},\
                "required":["subQuestions"]}""";

        public static final String ANSWER = """
                {"type":"object","properties":{"answer":{"type":"string"},"notInContext":{"type":"boolean"}},\
                "required":["answer","notInContext"]}""";

        private Schemas() {
        }

        /** The extraction schema, with the Entity types as an enum. */
        public static String extraction(List<String> types) {
            String typeEnum = LenientJson.toJson(List.copyOf(types));
            return ("{\"type\":\"object\",\"properties\":{"
                    + "\"entities\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
                    + "\"name\":{\"type\":\"string\"},\"type\":{\"type\":\"string\",\"enum\":%1$s},"
                    + "\"description\":{\"type\":\"string\"}},\"required\":[\"name\",\"type\",\"description\"]}},"
                    + "\"relationships\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{"
                    + "\"source\":{\"type\":\"string\"},\"sourceType\":{\"type\":\"string\",\"enum\":%1$s},"
                    + "\"type\":{\"type\":\"string\"},\"target\":{\"type\":\"string\"},"
                    + "\"targetType\":{\"type\":\"string\",\"enum\":%1$s},\"description\":{\"type\":\"string\"}},"
                    + "\"required\":[\"source\",\"sourceType\",\"type\",\"target\",\"targetType\",\"description\"]}}},"
                    + "\"required\":[\"entities\",\"relationships\"]}").formatted(typeEnum);
        }
    }
}
