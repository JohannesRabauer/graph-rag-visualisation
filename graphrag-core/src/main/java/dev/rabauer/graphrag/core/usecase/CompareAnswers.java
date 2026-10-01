package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Chunk;
import dev.rabauer.graphrag.core.domain.Citation;
import dev.rabauer.graphrag.core.domain.ComparisonFacts;
import dev.rabauer.graphrag.core.domain.ComparisonStats;
import dev.rabauer.graphrag.core.domain.ComparisonVerdict;
import dev.rabauer.graphrag.core.domain.EmbeddedChunk;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.port.VectorStorePort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Answers one question with a GraphRAG mode (Local, Global or DRIFT) and
 * with the Vector Search baseline, both fresh, and compares them: each
 * side's key figures ({@link ComparisonStats}), which passages both sides
 * retrieved, and a short verdict ({@link LlmPort#compareAnswers}).
 *
 * <p>A vector chunk counts as shared when its whitespace-normalized text is
 * contained in the text of a Text Unit the GraphRAG side retrieved (one of
 * its {@code TEXT_UNIT} steps) from the same document; a chunk with no
 * document name (an older corpus) matches on the text alone. A GraphRAG citation
 * counts as shared when its Text Unit contains a chunk the vector side
 * retrieved.
 *
 * <p>LLM failures of either answer propagate unchanged. A failing verdict
 * call does not fail the comparison: it falls back to
 * {@link ComparisonVerdict#ruleBased(ComparisonFacts)} and is logged.
 */
public class CompareAnswers {

    private static final System.Logger LOG = System.getLogger(CompareAnswers.class.getName());

    /** The GraphRAG modes a comparison can run. */
    public enum Mode {
        LOCAL, GLOBAL, DRIFT;

        /**
         * @return the mode named {@code name} (case-insensitive, trimmed); LOCAL
         *         when {@code name} is null or blank; empty when it names none
         */
        public static Optional<Mode> parse(String name) {
            if (name == null || name.isBlank()) {
                return Optional.of(LOCAL);
            }
            try {
                return Optional.of(valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    private static final Set<RetrievalStep.Kind> GRAPH_CONTEXT_KINDS = EnumSet.of(
            RetrievalStep.Kind.ENTITY, RetrievalStep.Kind.RELATIONSHIP,
            RetrievalStep.Kind.COMMUNITY, RetrievalStep.Kind.TEXT_UNIT);

    private static final LlmPort DEFAULT_LLM_PORT = corpus -> new GraphExtraction(List.of(), List.of());

    private final GraphStorePort graphStorePort;
    private final EmbeddingPort embeddingPort;
    private final LlmPort llmPort;
    private final VectorStorePort vectorStorePort;
    private final AnswerVectorBaseline answerVectorBaseline;

    /**
     * @param graphStorePort       the knowledge graph the GraphRAG side reads
     * @param embeddingPort        as for {@link AnswerLocalSearch}; may be null
     * @param llmPort              answers the GraphRAG side and writes the
     *                             verdict; null keeps templated answers and
     *                             the rule-based verdict
     * @param vectorStorePort      where the vector side's chunks are read back
     *                             from for the overlap
     * @param answerVectorBaseline answers the vector side
     */
    public CompareAnswers(GraphStorePort graphStorePort, EmbeddingPort embeddingPort, LlmPort llmPort,
                          VectorStorePort vectorStorePort, AnswerVectorBaseline answerVectorBaseline) {
        this.graphStorePort = Objects.requireNonNull(graphStorePort, "graphStorePort must not be null");
        this.embeddingPort = embeddingPort;
        this.llmPort = llmPort == null ? DEFAULT_LLM_PORT : llmPort;
        this.vectorStorePort = Objects.requireNonNull(vectorStorePort, "vectorStorePort must not be null");
        this.answerVectorBaseline = Objects.requireNonNull(answerVectorBaseline,
                "answerVectorBaseline must not be null");
    }

    /** The GraphRAG side of a comparison, independent of the mode that produced it. */
    public record GraphSide(Mode mode, boolean noAnswer, String answer, String reason, List<RetrievalStep> steps,
                            List<Citation> citations, ComparisonStats stats) {
        public GraphSide {
            steps = steps == null ? List.of() : List.copyOf(steps);
            citations = citations == null ? List.of() : List.copyOf(citations);
        }
    }

    /** The Vector Search side of a comparison. */
    public record VectorSide(VectorBaselineAnswer answer, ComparisonStats stats) {
    }

    /**
     * The whole comparison.
     *
     * @param graphCitationShared  per GraphRAG citation, in order, whether the
     *                             vector side also retrieved that passage
     * @param vectorCitationShared per vector citation, in order, whether the
     *                             GraphRAG side also retrieved that passage
     * @param graphRetrieved       the Text Units the GraphRAG side read (its
     *                             {@code TEXT_UNIT} steps), in step order
     * @param vectorRetrieved      the chunks the vector side retrieved (its
     *                             {@code VECTOR_CHUNK} steps), in step order
     */
    public record Comparison(String question, GraphSide graph, VectorSide vector, List<Boolean> graphCitationShared,
                             List<Boolean> vectorCitationShared, ComparisonFacts facts, ComparisonVerdict verdict,
                             List<RetrievedPassage> graphRetrieved, List<RetrievedPassage> vectorRetrieved) {
        public Comparison {
            graphCitationShared = graphCitationShared == null ? List.of() : List.copyOf(graphCitationShared);
            vectorCitationShared = vectorCitationShared == null ? List.of() : List.copyOf(vectorCitationShared);
            graphRetrieved = graphRetrieved == null ? List.of() : List.copyOf(graphRetrieved);
            vectorRetrieved = vectorRetrieved == null ? List.of() : List.copyOf(vectorRetrieved);
        }
    }

    /**
     * One passage a side retrieved, cited or not.
     *
     * @param id           the Text Unit id (GraphRAG) or chunk id (Vector Search)
     * @param documentName the passage's document; {@code ""} when unknown
     * @param excerpt      the first 200 characters, whitespace-collapsed
     * @param shared       whether the other side also retrieved this passage
     */
    public record RetrievedPassage(String id, String documentName, String excerpt, boolean shared) {
        public RetrievedPassage {
            id = nullToEmpty(id);
            documentName = nullToEmpty(documentName);
            excerpt = nullToEmpty(excerpt);
        }
    }

    public Comparison compare(String question, String corpusId, Mode mode) {
        Mode graphMode = mode == null ? Mode.LOCAL : mode;

        long graphStart = System.nanoTime();
        GraphAnswer graphAnswer = answerGraph(question, corpusId, graphMode);
        long graphLatency = elapsedMs(graphStart);

        long vectorStart = System.nanoTime();
        VectorBaselineAnswer vectorAnswer = answerVectorBaseline.answer(question, corpusId);
        long vectorLatency = elapsedMs(vectorStart);

        // The passages each side retrieved, with their documents.
        Map<String, TextUnit> graphUnits = graphTextUnits(corpusId, graphAnswer.steps());
        Map<String, Chunk> vectorChunks = vectorChunks(corpusId, vectorAnswer.steps());

        Set<String> sharedChunkIds = new LinkedHashSet<>();
        Set<String> sharedUnitIds = new LinkedHashSet<>();
        for (Chunk chunk : vectorChunks.values()) {
            String chunkText = normalize(chunk.text());
            if (chunkText.isEmpty()) {
                continue;
            }
            for (TextUnit unit : graphUnits.values()) {
                if (sameDocument(chunk.documentName(), unit.documentName())
                        && normalize(unit.text()).contains(chunkText)) {
                    sharedChunkIds.add(chunk.id());
                    sharedUnitIds.add(unit.id());
                }
            }
        }

        // Distinct (kind, identifier) pairs: DRIFT revisits entities and passages across branches.
        int graphContextItems = (int) graphAnswer.steps().stream()
                .filter(step -> GRAPH_CONTEXT_KINDS.contains(step.kind()))
                .map(step -> step.kind() + "|" + step.identifier())
                .distinct().count();
        int graphDocuments = distinct(graphUnits.values().stream().map(TextUnit::documentName).toList());
        ComparisonStats graphStats = new ComparisonStats(graphContextItems, graphDocuments, graphLatency);

        int vectorContextItems = (int) vectorAnswer.steps().stream()
                .filter(step -> step.kind() == RetrievalStep.Kind.VECTOR_CHUNK).count();
        int vectorDocuments = distinct(vectorChunks.values().stream().map(Chunk::documentName).toList());
        ComparisonStats vectorStats = new ComparisonStats(vectorContextItems, vectorDocuments, vectorLatency);

        GraphSide graphSide = new GraphSide(graphMode, graphAnswer.noAnswer(), graphAnswer.answer(),
                graphAnswer.reason(), graphAnswer.steps(), graphAnswer.citations(), graphStats);
        VectorSide vectorSide = new VectorSide(vectorAnswer, vectorStats);

        List<Boolean> graphShared = graphSide.citations().stream()
                .map(citation -> sharedUnitIds.contains(citation.textUnitId())).toList();
        List<Boolean> vectorShared = vectorAnswer.citations().stream()
                .map(citation -> sharedChunkIds.contains(citation.textUnitId())).toList();

        ComparisonFacts facts = new ComparisonFacts(graphMode.name(), graphStats, vectorStats,
                vectorChunks.size(), sharedChunkIds.size());
        ComparisonVerdict verdict = verdict(question, graphSide, vectorAnswer, facts);

        List<RetrievedPassage> graphRetrieved = graphUnits.values().stream()
                .map(unit -> new RetrievedPassage(unit.id(), unit.documentName(),
                        LocalContextAssembler.excerpt(unit.text()), sharedUnitIds.contains(unit.id())))
                .toList();
        List<RetrievedPassage> vectorRetrieved = vectorChunks.values().stream()
                .map(chunk -> new RetrievedPassage(chunk.id(), chunk.documentName(),
                        LocalContextAssembler.excerpt(chunk.text()), sharedChunkIds.contains(chunk.id())))
                .toList();

        return new Comparison(question, graphSide, vectorSide, graphShared, vectorShared, facts, verdict,
                graphRetrieved, vectorRetrieved);
    }

    private ComparisonVerdict verdict(String question, GraphSide graph, VectorBaselineAnswer vector,
                                      ComparisonFacts facts) {
        String graphText = graph.noAnswer() ? "(no answer: " + nullToEmpty(graph.reason()) + ")" : graph.answer();
        String vectorText = vector.noAnswer() ? "(no answer: " + nullToEmpty(vector.reason()) + ")" : vector.answer();
        try {
            ComparisonVerdict verdict = llmPort.compareAnswers(question, graphText, vectorText, facts);
            if (verdict == null || verdict.text().isBlank()) {
                return ComparisonVerdict.ruleBased(facts);
            }
            return verdict;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "The comparison verdict call failed; falling back to the rule-based summary: "
                            + e.getMessage(), e);
            return ComparisonVerdict.ruleBased(facts);
        }
    }

    /** One GraphRAG answer, independent of its mode. */
    private record GraphAnswer(boolean noAnswer, String answer, String reason, List<RetrievalStep> steps,
                               List<Citation> citations) {
    }

    private GraphAnswer answerGraph(String question, String corpusId, Mode mode) {
        return switch (mode) {
            case GLOBAL -> {
                GlobalSearchAnswer result = new AnswerGlobalSearch(graphStorePort, embeddingPort, llmPort)
                        .answer(question, corpusId);
                yield new GraphAnswer(result.noAnswer(), result.answer(), result.reason(), result.steps(),
                        result.citations());
            }
            case DRIFT -> {
                DriftSearchAnswer result = new AnswerDriftSearch(graphStorePort, llmPort, embeddingPort)
                        .answer(question, corpusId);
                yield new GraphAnswer(result.noAnswer(), result.answer(), result.reason(), result.steps(),
                        result.citations());
            }
            case LOCAL -> {
                LocalSearchAnswer result = new AnswerLocalSearch(graphStorePort, embeddingPort, llmPort)
                        .answer(question, corpusId);
                yield new GraphAnswer(result.noAnswer(), result.answer(), result.reason(), result.steps(),
                        result.citations());
            }
        };
    }

    /** The Text Units of the GraphRAG side's {@code TEXT_UNIT} steps, by id, in step order. */
    private Map<String, TextUnit> graphTextUnits(String corpusId, List<RetrievalStep> steps) {
        Map<String, TextUnit> units = new LinkedHashMap<>();
        for (RetrievalStep step : steps) {
            if (step.kind() != RetrievalStep.Kind.TEXT_UNIT || units.containsKey(step.identifier())) {
                continue;
            }
            LocalContextAssembler.loadTextUnit(graphStorePort, corpusId, step.identifier())
                    .filter(unit -> unit.text() != null)
                    .ifPresent(unit -> units.put(step.identifier(), unit));
        }
        return units;
    }

    /** The chunks of the vector side's {@code VECTOR_CHUNK} steps, by id, in step order. */
    private Map<String, Chunk> vectorChunks(String corpusId, List<RetrievalStep> steps) {
        Map<String, Chunk> byId = new LinkedHashMap<>();
        Collection<EmbeddedChunk> stored = vectorStorePort.chunks(corpusId);
        if (stored != null) {
            for (EmbeddedChunk embedded : stored) {
                if (embedded != null && embedded.chunk() != null) {
                    byId.putIfAbsent(embedded.chunk().id(), embedded.chunk());
                }
            }
        }
        Map<String, Chunk> retrieved = new LinkedHashMap<>();
        for (RetrievalStep step : steps) {
            if (step.kind() != RetrievalStep.Kind.VECTOR_CHUNK) {
                continue;
            }
            Chunk chunk = byId.get(step.identifier());
            if (chunk != null) {
                retrieved.putIfAbsent(chunk.id(), chunk);
            }
        }
        return retrieved;
    }

    /** Whitespace collapsed to single spaces, trimmed. */
    static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    /**
     * Whether a chunk and a Text Unit come from the same document. A chunk
     * with no document name (indexed before chunks knew their document) is
     * unknown, so it matches on text containment alone.
     */
    private static boolean sameDocument(String chunkDocument, String unitDocument) {
        String chunk = nullToEmpty(chunkDocument);
        return chunk.isEmpty() || chunk.equals(nullToEmpty(unitDocument));
    }

    /** How many distinct, known document names there are ({@code ""} is unknown and not counted). */
    private static int distinct(List<String> documentNames) {
        return (int) documentNames.stream().map(CompareAnswers::nullToEmpty)
                .filter(name -> !name.isEmpty()).distinct().count();
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
