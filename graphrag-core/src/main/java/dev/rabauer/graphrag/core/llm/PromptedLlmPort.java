package dev.rabauer.graphrag.core.llm;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityPoint;
import dev.rabauer.graphrag.core.domain.CommunitySummary;
import dev.rabauer.graphrag.core.domain.ComparisonFacts;
import dev.rabauer.graphrag.core.domain.ComparisonStats;
import dev.rabauer.graphrag.core.domain.ComparisonVerdict;
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
import java.util.Locale;
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
 * <p>Map-reduce Global Search ({@link #mapCommunities}) runs whenever
 * answer synthesis is on.
 *
 * <p>Capabilities: Community summaries, DRIFT sub-questions and the
 * GraphRAG-vs-Vector verdict ({@link #compareAnswers}) are always
 * model-backed; extraction, answer synthesis and description summaries are
 * switched by {@link Options#extraction()}, {@link Options#synthesis()} and
 * {@link Options#descriptionSummaries()} (all off by default: an exact,
 * imported graph needs none of them). With
 * {@link Options#gleanings()} above zero, extraction asks the model again,
 * in the same conversation, for what its earlier replies missed.
 */
public abstract class PromptedLlmPort implements LlmPort {

    static final int MAX_DESCRIPTION_CHARS = 300;
    static final int MAX_CONTEXT_ITEM_CHARS = 1500;
    static final int MAX_VERDICT_ANSWER_CHARS = 1500;
    static final int MAX_VERDICT_SENTENCES = 2;
    static final int MAX_POINTS_PER_COMMUNITY = 3;

    static final String GLEANING_PROMPT = """
            Some entities and relationships in the passage were missed. Reply with one JSON object of the \
            same shape that lists only the missing ones, following the same rules. Use the exact names \
            already given for entities listed before. If nothing is missing, reply \
            {"entities":[],"relationships":[]}.""";

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
        return extract(unit, entityTypes, List.of());
    }

    /** Lists {@code knownEntityNames} in the prompt so the model reuses their spelling. */
    @Override
    public GraphExtraction extract(TextUnit unit, List<String> entityTypes, List<String> knownEntityNames) {
        if (!options.extraction() || unit == null || unit.text() == null || unit.text().isBlank()) {
            return GraphExtraction.empty();
        }
        List<String> types = entityTypes == null || entityTypes.isEmpty() ? EntityTypes.ALL : entityTypes;
        String schema = Schemas.extraction(types);
        Exchange<GraphExtraction> first = exchange(Purpose.EXTRACTION,
                List.of(new Message(Role.USER, extractionPrompt(unit, types, knownEntityNames))), schema,
                options.extractionTokens(),
                object -> parseExtraction(object, types));
        return glean(first, types, schema);
    }

    String extractionPrompt(TextUnit unit, List<String> types, List<String> knownEntityNames) {
        String known = knownEntityNames == null || knownEntityNames.isEmpty() ? "" : """
                Entities found in earlier passages; when the passage means one of them, use its name exactly:
                %s

                """.formatted(String.join("; ", knownEntityNames));
        return """
                Extract a knowledge graph from the passage: the entities it names and the relationships \
                between them.
                Rules:
                - Entity types: %s. Use "%s" when no other type fits.
                - Name each entity by its most complete name in the passage (for example "Ada Lovelace", \
                not "Ada" or "she") and spell it the same way every time.
                - Extract only specific things the passage says something about; skip pronouns and \
                generic nouns.
                - Every relationship's source and target must be an entity listed in "entities", with the \
                same name and type.
                - Write a relationship type as a short lowercase verb phrase joined by underscores, for \
                example "works_for" or "located_in".
                - Each description is one sentence, using only what the passage says.
                Reply with one JSON object and nothing else, shaped like:
                {"entities":[{"name":"...","type":"...","description":"one sentence"}],\
                "relationships":[{"source":"...","sourceType":"...","type":"verb_phrase","target":"...",\
                "targetType":"...","description":"one sentence"}]}

                %sPassage (%s, part %d):
                %s""".formatted(String.join(", ", types), types.getLast(), known, unit.documentName(),
                unit.ordinal() + 1, unit.text());
    }

    /**
     * Up to {@link Options#gleanings()} more turns of the extraction
     * conversation, each asking only for what the replies so far missed. A
     * turn that adds nothing new, or whose reply cannot be used, ends
     * gleaning and keeps what was found; a failure of {@link #complete}
     * propagates.
     */
    private GraphExtraction glean(Exchange<GraphExtraction> first, List<String> types, String schema) {
        if (options.gleanings() == 0) {
            return first.value();
        }
        Map<String, Entity> entities = new LinkedHashMap<>();
        Map<String, Relationship> relationships = new LinkedHashMap<>();
        boolean grew = addExtraction(first.value(), entities, relationships);
        List<Message> conversation = new ArrayList<>(first.messages());
        for (int round = 0; round < options.gleanings() && grew; round++) {
            conversation.add(new Message(Role.USER, GLEANING_PROMPT));
            String reply = complete(new CompletionRequest(Purpose.EXTRACTION, List.copyOf(conversation), schema,
                    options.extractionTokens()));
            Optional<GraphExtraction> more = LenientJson.parseObject(reply)
                    .flatMap(result -> parseExtraction(result.object(), types));
            if (more.isEmpty()) {
                break;
            }
            conversation.add(new Message(Role.ASSISTANT, reply));
            grew = addExtraction(more.get(), entities, relationships);
        }
        return new GraphExtraction(List.copyOf(entities.values()), List.copyOf(relationships.values()));
    }

    /** Merges {@code extraction} in; whether it held an element not seen before. */
    private static boolean addExtraction(GraphExtraction extraction, Map<String, Entity> entities,
                                         Map<String, Relationship> relationships) {
        boolean added = false;
        for (Entity entity : extraction.entities()) {
            added |= !entities.containsKey(entity.normalizedIdentity());
            entities.merge(entity.normalizedIdentity(), entity, GraphElementMerger::merge);
        }
        for (Relationship relationship : extraction.relationships()) {
            String key = relationship.sourceIdentity() + "|" + relationship.type().toLowerCase(Locale.ROOT) + "|"
                    + relationship.targetIdentity();
            added |= !relationships.containsKey(key);
            relationships.merge(key, relationship, GraphElementMerger::merge);
        }
        return added;
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

    // -- Description summaries -------------------------------------------------------------

    @Override
    public boolean summarizesDescriptions() {
        return options.descriptionSummaries();
    }

    /**
     * One call that merges an element's descriptions into one, when
     * {@link Options#descriptionSummaries()} is on; otherwise
     * {@code description} unchanged.
     */
    @Override
    public String summarizeDescription(String elementName, String description) {
        if (!options.descriptionSummaries() || description == null || description.isBlank()) {
            return LlmPort.super.summarizeDescription(elementName, description);
        }
        return call(Purpose.DESCRIPTION_SUMMARY, descriptionSummaryPrompt(elementName, description),
                Schemas.DESCRIPTION_SUMMARY, options.summaryTokens(), object -> {
                    String summary = LenientJson.string(object, "description");
                    return summary.isEmpty() ? Optional.empty() : Optional.of(summary);
                });
    }

    String descriptionSummaryPrompt(String elementName, String description) {
        return """
                The notes below describe one element of a knowledge graph, collected from several passages.
                Merge them into one description of at most %d characters: keep every distinct fact that \
                fits, most important first, drop repetitions, and use only the notes.
                Reply with one JSON object and nothing else, shaped like:
                {"description":"..."}

                Element: %s
                Notes:
                %s""".formatted(GraphElementMerger.DESCRIPTION_LIMIT, elementName == null ? "" : elementName.trim(),
                description.trim());
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
                    .append(relationship.type()).append("]-> ").append(relationship.target());
            String description = truncate(relationship.description(), MAX_DESCRIPTION_CHARS);
            if (!description.isEmpty()) {
                lines.append(": ").append(description);
            }
            lines.append('\n');
        }
        if (index == 1) {
            lines.append("(none)\n");
        }
        return """
                Summarize this group of connected elements, using only the members and relationships below.
                The title names the group's shared theme. The summary says what the group is about, which \
                members matter most and how they relate, naming members exactly as written.
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
                (%d questions). Each follow-up asks what that group contributes to the question, so it \
                can be answered from that group alone. Keep names and identifiers from the question \
                exactly as written.
                Reply with one JSON object and nothing else, shaped like:
                {"subQuestions":["...","..."]}

                Question: %s
                Groups:
                %s""".formatted(communities.size(), question == null ? "" : question.trim(), lines);
    }

    // -- Global Search map step -------------------------------------------------------------

    /** Map-reduce Global Search runs whenever answers are synthesized. */
    @Override
    public boolean mapsCommunities() {
        return options.synthesis();
    }

    /**
     * One call per batch: up to {@value #MAX_POINTS_PER_COMMUNITY} scored key
     * points per Community. A point naming no Community of the batch is
     * dropped; an empty {@code points} array is a valid reply.
     */
    @Override
    public List<CommunityPoint> mapCommunities(String question, List<Community> communities) {
        if (!options.synthesis() || communities == null || communities.isEmpty()) {
            return List.of();
        }
        List<Community> batch = communities.stream().filter(Objects::nonNull).toList();
        return call(Purpose.COMMUNITY_POINTS, communityPointsPrompt(question, batch), Schemas.COMMUNITY_POINTS,
                options.answerTokens(), object -> {
                    if (!object.containsKey("points")) {
                        return Optional.empty();
                    }
                    List<CommunityPoint> points = new ArrayList<>();
                    for (Map<String, Object> node : LenientJson.objects(object, "points")) {
                        int index = (int) LenientJson.number(node, "community").orElse(0);
                        String text = LenientJson.string(node, "point");
                        if (index >= 1 && index <= batch.size() && !text.isEmpty()) {
                            points.add(new CommunityPoint(batch.get(index - 1).id(), text,
                                    (int) Math.round(LenientJson.number(node, "score").orElse(0))));
                        }
                    }
                    return Optional.of(List.copyOf(points));
                });
    }

    String communityPointsPrompt(String question, List<Community> communities) {
        StringBuilder groups = new StringBuilder();
        int index = 1;
        for (Community community : communities) {
            groups.append('[').append(index++).append("] ")
                    .append(community.title().isEmpty() ? "" : community.title() + ": ")
                    .append(truncate(community.summary(), MAX_CONTEXT_ITEM_CHARS)).append('\n');
        }
        return """
                Each numbered group below summarizes one part of a document collection. For each group, \
                write up to %d key points that help answer the question, using only that group's text, and \
                score each from 0 (does not help) to 100 (answers the question directly). Leave out groups \
                that do not help; reply with an empty "points" array if none do.
                Reply with one JSON object and nothing else, shaped like:
                {"points":[{"community":1,"point":"one or two sentences","score":80}]}

                Groups:
                %s
                Question: %s""".formatted(MAX_POINTS_PER_COMMUNITY, groups, question == null ? "" : question.trim());
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
                Answer the question using only the numbered context below, not outside knowledge.
                Rules:
                - Cite every claim inline with the numbers of the "Source passage" items that support it, \
                written as [n], for example [3] or [4][5].
                - Entity, Relationship and Community summary items are background facts: use them, but \
                never cite them.
                - If the context answers only part of the question, answer that part and say what the \
                context does not cover.
                - If the context does not answer the question at all, set "answer" to "%s" and \
                "notInContext" to true.
                Reply with one JSON object and nothing else, shaped like:
                {"answer":"...","notInContext":false}

                Context:
                %s
                Question: %s""".formatted(SynthesizedAnswer.NOT_IN_CONTEXT,
                items.isEmpty() ? "(no context)\n" : items, question == null ? "" : question.trim());
    }

    // -- Comparison verdict ------------------------------------------------------------------

    /**
     * One short model call naming the concrete difference between a GraphRAG
     * answer and a Vector Search answer and its reason, grounded in the
     * measured {@code facts}. An unusable reply fails with
     * {@link LlmReplyException}; {@code CompareAnswers} then falls back to
     * {@link ComparisonVerdict#ruleBased(ComparisonFacts)}.
     */
    @Override
    public ComparisonVerdict compareAnswers(String question, String graphAnswer, String vectorAnswer,
                                            ComparisonFacts facts) {
        return call(Purpose.VERDICT, verdictPrompt(question, graphAnswer, vectorAnswer, facts), Schemas.VERDICT,
                options.verdictTokens(), object -> {
                    String verdict = LenientJson.string(object, "verdict");
                    return verdict.isEmpty() ? Optional.empty()
                            : Optional.of(new ComparisonVerdict(verdict, ComparisonVerdict.Source.LLM));
                });
    }

    String verdictPrompt(String question, String graphAnswer, String vectorAnswer, ComparisonFacts facts) {
        ComparisonFacts f = facts == null ? new ComparisonFacts(null, null, null, 0, 0) : facts;
        return """
                Two answers to the same question follow. GraphRAG (%s search) answered from a knowledge \
                graph of entities, relationships, community summaries and source passages; Vector Search \
                answered from the text chunks most similar to the question.
                In at most %d short sentences, name the concrete difference between the answers and its \
                reason, using only the answers and the measured facts. Do not say which answer is correct; \
                say what each side retrieved and what that changed.
                Reply with one JSON object and nothing else, shaped like:
                {"verdict":"..."}

                Measured facts:
                - GraphRAG: %s
                - Vector Search: %s
                - %d of the %d passages Vector Search retrieved were also read by GraphRAG.
                Question: %s
                GraphRAG answer:
                %s
                Vector Search answer:
                %s""".formatted(f.graphMode(), MAX_VERDICT_SENTENCES, statsLine(f.graph()), statsLine(f.vector()),
                f.sharedPassages(), f.vectorPassages(), question == null ? "" : question.trim(),
                truncate(graphAnswer, MAX_VERDICT_ANSWER_CHARS), truncate(vectorAnswer, MAX_VERDICT_ANSWER_CHARS));
    }

    private static String statsLine(ComparisonStats stats) {
        return stats.contextItems() + " context items from " + stats.distinctDocuments() + " distinct documents, "
                + stats.latencyMs() + " ms";
    }

    // -- The call ---------------------------------------------------------------------------

    /**
     * Asks once, parses leniently, asks once more with a corrective message
     * when the reply is unusable, then gives up with {@link LlmReplyException}.
     */
    private <T> T call(Purpose purpose, String prompt, String schema, int maxTokens,
                       Function<Map<String, Object>, Optional<T>> parse) {
        return exchange(purpose, List.of(new Message(Role.USER, prompt)), schema, maxTokens, parse).value();
    }

    /**
     * {@link #call} from a given conversation; also returns the conversation
     * up to and including the reply that was used.
     */
    private <T> Exchange<T> exchange(Purpose purpose, List<Message> conversation, String schema, int maxTokens,
                                     Function<Map<String, Object>, Optional<T>> parse) {
        List<Message> messages = new ArrayList<>(conversation);
        String reply = "";
        int attempts = options.correctiveRetry() ? 2 : 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            reply = complete(new CompletionRequest(purpose, List.copyOf(messages), schema, maxTokens));
            Optional<LenientJson.Result> parsed = LenientJson.parseObject(reply);
            Optional<T> value = parsed.flatMap(result -> parse.apply(result.object()));
            if (value.isPresent()) {
                messages.add(new Message(Role.ASSISTANT, reply));
                return new Exchange<>(value.get(), List.copyOf(messages));
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

    /** A parsed reply and the conversation that produced it. */
    private record Exchange<T>(T value, List<Message> messages) {
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
        ANSWER,
        /** The GraphRAG-vs-Vector verdict ({@link #compareAnswers}). */
        VERDICT,
        /** One description from an element's merged descriptions ({@link #summarizeDescription}). */
        DESCRIPTION_SUMMARY,
        /** The map step of map-reduce Global Search ({@link #mapCommunities}). */
        COMMUNITY_POINTS
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
     * @param gleanings         how many extra extraction turns ask for the
     *                          Entities and Relationships missed so far (0,
     *                          the default: one pass; each turn is one more
     *                          call per Text Unit)
     * @param verdictTokens     output-token limit suggested for the
     *                          comparison verdict
     * @param descriptionSummaries whether {@link #summarizeDescription(String, String)}
     *                          calls the model (off by default; one call per
     *                          element whose merged description outgrew its limit)
     */
    public record Options(boolean extraction, boolean synthesis, boolean correctiveRetry, int extractionTokens,
                          int summaryTokens, int subQuestionTokens, int answerTokens, int gleanings,
                          int verdictTokens, boolean descriptionSummaries) {

        public Options {
            if (gleanings < 0) {
                throw new IllegalArgumentException("gleanings must not be negative: " + gleanings);
            }
        }

        /** Without gleaning or description summaries, with the default verdict token limit (256). */
        public Options(boolean extraction, boolean synthesis, boolean correctiveRetry, int extractionTokens,
                       int summaryTokens, int subQuestionTokens, int answerTokens) {
            this(extraction, synthesis, correctiveRetry, extractionTokens, summaryTokens, subQuestionTokens,
                    answerTokens, 0, 256, false);
        }

        /**
         * Summaries, sub-questions and verdicts only, with the corrective
         * retry, no gleaning and no description summaries.
         */
        public static Options defaults() {
            return new Options(false, false, true, 4096, 512, 512, 1024, 0, 256, false);
        }

        public Options withExtraction(boolean value) {
            return new Options(value, synthesis, correctiveRetry, extractionTokens, summaryTokens, subQuestionTokens,
                    answerTokens, gleanings, verdictTokens, descriptionSummaries);
        }

        public Options withSynthesis(boolean value) {
            return new Options(extraction, value, correctiveRetry, extractionTokens, summaryTokens, subQuestionTokens,
                    answerTokens, gleanings, verdictTokens, descriptionSummaries);
        }

        public Options withCorrectiveRetry(boolean value) {
            return new Options(extraction, synthesis, value, extractionTokens, summaryTokens, subQuestionTokens,
                    answerTokens, gleanings, verdictTokens, descriptionSummaries);
        }

        /** {@code count} extra extraction turns for missed elements; 1 is usually enough. */
        public Options withGleanings(int count) {
            return new Options(extraction, synthesis, correctiveRetry, extractionTokens, summaryTokens,
                    subQuestionTokens, answerTokens, count, verdictTokens, descriptionSummaries);
        }

        /** Whether descriptions that outgrow their limit are summarised by the model. */
        public Options withDescriptionSummaries(boolean value) {
            return new Options(extraction, synthesis, correctiveRetry, extractionTokens, summaryTokens,
                    subQuestionTokens, answerTokens, gleanings, verdictTokens, value);
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

        public static final String COMMUNITY_POINTS = """
                {"type":"object","properties":{"points":{"type":"array","items":{"type":"object","properties":{\
                "community":{"type":"integer"},"point":{"type":"string"},"score":{"type":"integer"}},\
                "required":["community","point","score"]}}},"required":["points"]}""";

        public static final String DESCRIPTION_SUMMARY = """
                {"type":"object","properties":{"description":{"type":"string"}},"required":["description"]}""";

        public static final String VERDICT = """
                {"type":"object","properties":{"verdict":{"type":"string"}},"required":["verdict"]}""";

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
