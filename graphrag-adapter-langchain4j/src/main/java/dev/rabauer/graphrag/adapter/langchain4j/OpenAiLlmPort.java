package dev.rabauer.graphrag.adapter.langchain4j;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityPoint;
import dev.rabauer.graphrag.core.domain.CommunitySummary;
import dev.rabauer.graphrag.core.domain.ComparisonFacts;
import dev.rabauer.graphrag.core.domain.ComparisonVerdict;
import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.SynthesizedAnswer;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.llm.LlmReplyException;
import dev.rabauer.graphrag.core.llm.PromptedLlmPort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.FinishReason;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real, network-backed {@link LlmPort} implementation that calls the OpenAI
 * Chat Completions API (via LangChain4j).
 *
 * <p>The prompts, JSON Schemas and reply parsing are the core's
 * ({@link PromptedLlmPort}); this adapter only sends a conversation to OpenAI
 * in JSON mode ({@link #complete(CompletionRequest)}). Per the architecture's
 * hard rule (AD-3), only this adapter module may reference LangChain4j/OpenAI
 * types; {@code graphrag-core} and every other module only ever see the
 * framework-free {@link LlmPort} interface.
 *
 * <p>Failures are never retried automatically ({@code maxRetries(0)}) so
 * they surface visibly to the caller instead of silently masking a broken
 * or misconfigured integration. Every failure — a network or API error, a
 * reply cut off by the output-token limit, or a reply that stays unusable —
 * is an {@link LlmCallFailedException}. With the corrective retry switched on
 * (off by default), an unusable reply is asked once more, with the bad reply
 * and a correction; a network or API failure is never retried.
 *
 * <p>Extraction and answer synthesis are always on. DRIFT sub-questions stay
 * the deterministic default (question plus Community summary), which Local
 * Search's keyword and semantic seed matching rely on.
 */
public class OpenAiLlmPort extends PromptedLlmPort {

    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    static final int MAX_EXTRACTION_OUTPUT_TOKENS = 4096;
    static final int MAX_VERDICT_SENTENCES = 2;

    private final ChatModel jsonChatModel;

    public OpenAiLlmPort(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public OpenAiLlmPort(String apiKey, String modelName) {
        this(apiKey, modelName, false);
    }

    /**
     * @param correctiveRetry whether an unusable reply (no JSON object, a
     *                        required field missing) is asked once more with a
     *                        correction before failing the call
     */
    public OpenAiLlmPort(String apiKey, String modelName, boolean correctiveRetry) {
        this(apiKey, modelName, Options.defaults().withCorrectiveRetry(correctiveRetry));
    }

    /**
     * @param options the core's options, for example
     *                {@link Options#withGleanings(int)}; extraction and
     *                synthesis are switched on whatever they say
     */
    public OpenAiLlmPort(String apiKey, String modelName, Options options) {
        this(jsonChatModel(apiKey, modelName), options);
    }

    OpenAiLlmPort(ChatModel jsonChatModel, Options options) {
        super(alwaysOn(options));
        this.jsonChatModel = jsonChatModel;
    }

    private static Options alwaysOn(Options options) {
        return (options == null ? Options.defaults() : options).withExtraction(true).withSynthesis(true);
    }

    private static ChatModel jsonChatModel(String apiKey, String modelName) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("OpenAI API key must not be blank");
        }
        // JSON mode needs the word "json" in the conversation; every core prompt and correction has it.
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName == null || modelName.isBlank() ? DEFAULT_MODEL : modelName)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(60))
                .maxTokens(MAX_EXTRACTION_OUTPUT_TOKENS)
                .maxRetries(0)
                .responseFormat("json_object")
                .build();
    }

    /**
     * One JSON-mode call. A failed call and a {@code length} finish throw
     * {@link LlmCallFailedException}, which the core propagates unchanged.
     */
    @Override
    protected String complete(CompletionRequest request) {
        String what = request.purpose().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        ChatResponse response;
        try {
            response = jsonChatModel.chat(ChatRequest.builder()
                    .messages(request.messages().stream().map(OpenAiLlmPort::toChatMessage).toList())
                    .maxOutputTokens(request.maxOutputTokens())
                    .build());
        } catch (RuntimeException e) {
            throw new LlmCallFailedException("OpenAI " + what + " call failed", e);
        }
        if (response != null && response.finishReason() == FinishReason.LENGTH) {
            throw new LlmCallFailedException("OpenAI " + what + " response hit the output-token limit ("
                    + request.maxOutputTokens() + ")", null);
        }
        return response == null || response.aiMessage() == null || response.aiMessage().text() == null
                ? "" : response.aiMessage().text();
    }

    private static ChatMessage toChatMessage(Message message) {
        return switch (message.role()) {
            case SYSTEM -> SystemMessage.from(message.text());
            case USER -> UserMessage.from(message.text());
            case ASSISTANT -> AiMessage.from(message.text().isEmpty() ? "(empty)" : message.text());
        };
    }

    // -- One exception type for every failure ------------------------------------------------

    @Override
    public GraphExtraction extract(TextUnit unit, List<String> entityTypes, List<String> knownEntityNames) {
        return failingVisibly(() -> super.extract(unit, entityTypes, knownEntityNames));
    }

    @Override
    public CommunitySummary summarizeCommunity(Collection<Entity> members, Collection<Relationship> relationships) {
        return failingVisibly(() -> super.summarizeCommunity(members, relationships));
    }

    @Override
    public List<CommunityPoint> mapCommunities(String question, List<Community> communities) {
        return failingVisibly(() -> super.mapCommunities(question, communities));
    }

    @Override
    public String summarizeDescription(String elementName, String description) {
        return failingVisibly(() -> super.summarizeDescription(elementName, description));
    }

    @Override
    public SynthesizedAnswer synthesizeAnswer(String question, List<ContextItem> context) {
        return failingVisibly(() -> super.synthesizeAnswer(question, context));
    }

    /** The model's verdict, cut to its first {@value #MAX_VERDICT_SENTENCES} sentences. */
    @Override
    public ComparisonVerdict compareAnswers(String question, String graphAnswer, String vectorAnswer,
                                            ComparisonFacts facts) {
        ComparisonVerdict verdict = failingVisibly(() -> super.compareAnswers(question, graphAnswer, vectorAnswer,
                facts));
        return new ComparisonVerdict(firstSentences(verdict.text(), MAX_VERDICT_SENTENCES), verdict.source());
    }

    @Override
    public boolean derivesSubQuestions() {
        return false;
    }

    @Override
    public List<String> deriveDriftSubQuestions(String question, Collection<Community> communities) {
        return LlmPort.none().deriveDriftSubQuestions(question, communities);
    }

    private static <T> T failingVisibly(Supplier<T> call) {
        try {
            return call.get();
        } catch (LlmReplyException unusable) {
            throw new LlmCallFailedException("OpenAI " + unusable.getMessage(), unusable);
        }
    }

    // -- Verdict sentences ---------------------------------------------------------------------

    private static final Pattern SENTENCE_END = Pattern.compile("[.!?](?=\\s|$)");

    /** Tokens whose trailing period does not end a sentence ("Mr. Holmes", "e.g. a clue"). */
    private static final Set<String> ABBREVIATIONS = Set.of(
            "mr", "mrs", "ms", "dr", "st", "prof", "sr", "jr", "rev", "capt", "col", "gen", "lt", "sgt",
            "mt", "no", "vs", "etc", "e.g", "i.e", "cf", "approx");

    /**
     * The first {@code count} sentences of {@code text} (each ending in ".",
     * "!" or "?" before whitespace). A period after a common abbreviation
     * (Mr., Dr., St., e.g., i.e. …) or a single uppercase initial does not
     * end a sentence.
     */
    static String firstSentences(String text, int count) {
        Matcher end = SENTENCE_END.matcher(text);
        int found = 0;
        while (end.find()) {
            if (text.charAt(end.start()) == '.' && isAbbreviation(text, end.start())) {
                continue;
            }
            found++;
            if (found == count) {
                return text.substring(0, end.end()).trim();
            }
        }
        return text.trim();
    }

    /** Whether the period at {@code period} closes an abbreviation or a single uppercase initial. */
    private static boolean isAbbreviation(String text, int period) {
        int start = period;
        while (start > 0 && (Character.isLetter(text.charAt(start - 1)) || text.charAt(start - 1) == '.')) {
            start--;
        }
        String token = text.substring(start, period);
        if (token.length() == 1 && Character.isUpperCase(token.charAt(0))) {
            return true;
        }
        return ABBREVIATIONS.contains(token.toLowerCase(Locale.ROOT));
    }

    /**
     * Signals that a call to the OpenAI API failed (network error, API
     * error, the output-token limit, or a reply that stays unusable).
     * Deliberately unchecked and never retried automatically — callers (see
     * {@code CorpusController}) surface this to the user instead of silently
     * masking it.
     */
    public static class LlmCallFailedException extends RuntimeException {
        public LlmCallFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
