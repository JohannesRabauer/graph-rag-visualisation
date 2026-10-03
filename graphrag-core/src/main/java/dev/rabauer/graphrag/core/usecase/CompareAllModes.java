package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Chunk;
import dev.rabauer.graphrag.core.domain.Citation;
import dev.rabauer.graphrag.core.domain.EmbeddedChunk;
import dev.rabauer.graphrag.core.domain.RankedChunk;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.StageTiming;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.port.VectorStorePort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Answers one question with all four retrieval methods (Local, Global,
 * DRIFT and the Vector Search baseline), one after the other on the same
 * corpus, and lines them up so a wrong answer can be traced to the stage
 * that caused it rather than blamed on "the graph".
 *
 * <p>Per method it records the answer and its citations, the traversal steps
 * (and a count per step kind), and where the time went ({@link StageTiming}:
 * retrieval, embedding and LLM). Across methods it builds one
 * <em>evidence table</em>: every passage any method put into its answer
 * context, ranked, or cited, and per method how far that passage got
 * ({@link Use}). A passage that never reached a method is a retrieval miss
 * for that method; a passage the vector ranking scored below its cut-off is a
 * ranking miss; a passage that was in the context but not cited is a
 * generation miss.
 *
 * <p>Passages are matched across methods like in {@link CompareAnswers}: a
 * vector chunk joins the row of a Text Unit a GraphRAG method read when the
 * chunk's whitespace-normalized text is contained in it (same document, or
 * any document for a chunk with none); otherwise it gets its own row.
 *
 * <p>The runs are sequential, so their latencies do not compete for the same
 * model or database. A run that throws does not stop the others: it is
 * reported as {@link Outcome#FAILED} with its exception.
 */
public class CompareAllModes {

    private static final System.Logger LOG = System.getLogger(CompareAllModes.class.getName());

    /** The four retrieval methods, in the order they run and are shown. */
    public enum Method {
        LOCAL, GLOBAL, DRIFT, VECTOR;

        /** The name a person reads: "Local", "Global", "DRIFT", "Vector Search". */
        public String label() {
            return switch (this) {
                case LOCAL -> "Local";
                case GLOBAL -> "Global";
                case DRIFT -> "DRIFT";
                case VECTOR -> "Vector Search";
            };
        }
    }

    /** How a run ended. */
    public enum Outcome {
        /** An answer was written. */
        ANSWERED,
        /** The method returned its no-answer shape (nothing matched, or "not in the context"). */
        NO_ANSWER,
        /** The run threw; see {@link MethodRun#failure()}. */
        FAILED
    }

    /**
     * How far one passage got in one method, weakest first, so the strongest
     * use wins when several chunks of a vector run fall into one row.
     */
    public enum Use {
        /** The method never reached this passage. */
        NOT_RETRIEVED,
        /** Vector Search scored it, but below the top-k cut-off: the model never saw it. */
        RANKED_BELOW_CUTOFF,
        /** The passage was in the answer's context, but the answer does not cite it. */
        IN_CONTEXT,
        /** The answer cites the passage. */
        CITED
    }

    /**
     * One method's run.
     *
     * @param answer           the answer text; null unless {@link Outcome#ANSWERED}
     * @param reason           why there is no answer; null when answered
     * @param footprint        how many steps of each kind the trace has, kinds
     *                         in {@link RetrievalStep.Kind} order, only kinds
     *                         that occur
     * @param ranking          the vector similarity ranking ({@link Method#VECTOR}
     *                         only, else empty)
     * @param scoredChunkCount how many chunks Vector Search scored (else 0)
     * @param failure          what a {@link Outcome#FAILED} run threw; else null
     */
    public record MethodRun(Method method, Outcome outcome, String answer, String reason,
                            List<RetrievalStep> steps, List<Citation> citations, StageTiming timing,
                            Map<RetrievalStep.Kind, Integer> footprint, List<RankedChunk> ranking,
                            int scoredChunkCount, RuntimeException failure) {
        public MethodRun {
            Objects.requireNonNull(method, "method must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
            steps = steps == null ? List.of() : List.copyOf(steps);
            citations = citations == null ? List.of() : List.copyOf(citations);
            timing = timing == null ? new StageTiming(0, 0, 0, 0, 0, 0) : timing;
            footprint = footprint == null ? Map.of() : java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(footprint));
            ranking = ranking == null ? List.of() : List.copyOf(ranking);
            scoredChunkCount = Math.max(0, scoredChunkCount);
        }
    }

    /** Whether an evidence row is a GraphRAG Text Unit or a vector chunk no Text Unit contains. */
    public enum PassageKind { TEXT_UNIT, CHUNK }

    /**
     * How far a passage got in one method.
     *
     * @param citationNumbers the answer's {@code [n]} markers that cite it, ascending
     * @param rank            the best vector ranking position of the passage
     *                        (1-based); 0 when not ranked or not Vector Search
     */
    public record Mark(Use use, List<Integer> citationNumbers, int rank) {
        public Mark {
            Objects.requireNonNull(use, "use must not be null");
            citationNumbers = citationNumbers == null ? List.of()
                    : citationNumbers.stream().distinct().sorted().toList();
            rank = Math.max(0, rank);
        }

        static final Mark NONE = new Mark(Use.NOT_RETRIEVED, List.of(), 0);
    }

    /**
     * One row of the evidence table.
     *
     * @param id      the Text Unit id or, for {@link PassageKind#CHUNK}, the chunk id
     * @param excerpt the first 200 characters, whitespace-collapsed
     * @param marks   one mark per method, in {@link Method} order
     */
    public record EvidenceRow(String id, PassageKind kind, String documentName, String excerpt,
                              Map<Method, Mark> marks) {
        public EvidenceRow {
            id = id == null ? "" : id;
            documentName = documentName == null ? "" : documentName;
            excerpt = excerpt == null ? "" : excerpt;
            Map<Method, Mark> all = new EnumMap<>(Method.class);
            for (Method method : Method.values()) {
                Mark mark = marks == null ? null : marks.get(method);
                all.put(method, mark == null ? Mark.NONE : mark);
            }
            marks = java.util.Collections.unmodifiableMap(all);
        }

        /** How many methods cite this passage. */
        public int citedBy() {
            return (int) marks.values().stream().filter(mark -> mark.use() == Use.CITED).count();
        }

        /** How many methods had this passage in their context (cited or not). */
        public int inContextOf() {
            return (int) marks.values().stream().filter(mark -> mark.use().compareTo(Use.IN_CONTEXT) >= 0).count();
        }
    }

    /**
     * The whole four-way comparison.
     *
     * @param runs     one per method, in {@link Method} order
     * @param evidence the evidence table: passages cited by more methods first,
     *                 then those in more methods' contexts, then in the order
     *                 the methods first touched them
     * @param summary  a short, deterministic summary of the measured facts
     */
    public record Comparison(String question, List<MethodRun> runs, List<EvidenceRow> evidence, String summary) {
        public Comparison {
            question = question == null ? "" : question;
            runs = runs == null ? List.of() : List.copyOf(runs);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            summary = summary == null ? "" : summary;
        }
    }

    private final GraphStorePort graphStorePort;
    private final EmbeddingPort embeddingPort;
    private final LlmPort llmPort;
    private final VectorStorePort vectorStorePort;
    private final AnswerVectorBaseline answerVectorBaseline;

    /**
     * @param graphStorePort       the knowledge graph the GraphRAG methods read
     * @param embeddingPort        as for {@link AnswerLocalSearch}; may be null
     * @param llmPort              answers every method; null keeps templated answers
     * @param vectorStorePort      where the vector chunks are read back from
     *                             for the evidence table
     * @param answerVectorBaseline answers the Vector Search method
     */
    public CompareAllModes(GraphStorePort graphStorePort, EmbeddingPort embeddingPort, LlmPort llmPort,
                           VectorStorePort vectorStorePort, AnswerVectorBaseline answerVectorBaseline) {
        this.graphStorePort = Objects.requireNonNull(graphStorePort, "graphStorePort must not be null");
        this.embeddingPort = embeddingPort;
        this.llmPort = llmPort;
        this.vectorStorePort = Objects.requireNonNull(vectorStorePort, "vectorStorePort must not be null");
        this.answerVectorBaseline = Objects.requireNonNull(answerVectorBaseline,
                "answerVectorBaseline must not be null");
    }

    public Comparison compare(String question, String corpusId) {
        List<MethodRun> runs = new ArrayList<>();
        for (Method method : Method.values()) {
            runs.add(run(method, question, corpusId));
        }
        List<EvidenceRow> evidence = evidence(corpusId, runs);
        return new Comparison(question, runs, evidence, summary(runs, evidence));
    }

    private MethodRun run(Method method, String question, String corpusId) {
        StageClock clock = new StageClock();
        LlmPort timedLlm = clock.llm(llmPort);
        EmbeddingPort timedEmbedding = clock.embedding(embeddingPort);
        try {
            return switch (method) {
                case LOCAL -> {
                    LocalSearchAnswer result = new AnswerLocalSearch(graphStorePort, timedEmbedding, timedLlm)
                            .answer(question, corpusId);
                    yield graphRun(method, result.noAnswer(), result.answer(), result.reason(), result.steps(),
                            result.citations(), clock.timing());
                }
                case GLOBAL -> {
                    GlobalSearchAnswer result = new AnswerGlobalSearch(graphStorePort, timedEmbedding, timedLlm)
                            .answer(question, corpusId);
                    yield graphRun(method, result.noAnswer(), result.answer(), result.reason(), result.steps(),
                            result.citations(), clock.timing());
                }
                case DRIFT -> {
                    DriftSearchAnswer result = new AnswerDriftSearch(graphStorePort, timedLlm, timedEmbedding)
                            .answer(question, corpusId);
                    yield graphRun(method, result.noAnswer(), result.answer(), result.reason(), result.steps(),
                            result.citations(), clock.timing());
                }
                case VECTOR -> {
                    VectorBaselineAnswer result = answerVectorBaseline.timed(clock).answer(question, corpusId);
                    StageTiming timing = clock.timing();
                    yield new MethodRun(method, result.noAnswer() ? Outcome.NO_ANSWER : Outcome.ANSWERED,
                            result.noAnswer() ? null : result.answer(),
                            result.noAnswer() ? nullToEmpty(result.reason()) : null,
                            result.steps(), result.citations(), timing, footprint(result.steps()),
                            result.ranking(), result.scoredChunkCount(), null);
                }
            };
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, method.label() + " failed during a four-way comparison; "
                    + "the other methods still run: " + e.getMessage(), e);
            return new MethodRun(method, Outcome.FAILED, null, nullToEmpty(e.getMessage()), List.of(), List.of(),
                    clock.timing(), Map.of(), List.of(), 0, e);
        }
    }

    private static MethodRun graphRun(Method method, boolean noAnswer, String answer, String reason,
                                      List<RetrievalStep> steps, List<Citation> citations, StageTiming timing) {
        return new MethodRun(method, noAnswer ? Outcome.NO_ANSWER : Outcome.ANSWERED,
                noAnswer ? null : nullToEmpty(answer), noAnswer ? nullToEmpty(reason) : null,
                steps, citations, timing, footprint(steps), List.of(), 0, null);
    }

    /** Step count per kind, in {@link RetrievalStep.Kind} order, only kinds that occur. */
    static Map<RetrievalStep.Kind, Integer> footprint(List<RetrievalStep> steps) {
        Map<RetrievalStep.Kind, Integer> counts = new EnumMap<>(RetrievalStep.Kind.class);
        for (RetrievalStep step : steps) {
            counts.merge(step.kind(), 1, Integer::sum);
        }
        return new LinkedHashMap<>(counts);
    }

    // ---- evidence table ----

    /** A row under construction. */
    private static final class Row {
        final String id;
        final PassageKind kind;
        final String documentName;
        final String text;
        final int firstSeen;
        final Map<Method, Use> uses = new EnumMap<>(Method.class);
        final Map<Method, List<Integer>> citations = new EnumMap<>(Method.class);
        int bestRank;

        Row(String id, PassageKind kind, String documentName, String text, int firstSeen) {
            this.id = id;
            this.kind = kind;
            this.documentName = nullToEmpty(documentName);
            this.text = nullToEmpty(text);
            this.firstSeen = firstSeen;
        }

        void mark(Method method, Use use) {
            uses.merge(method, use, (a, b) -> a.compareTo(b) >= 0 ? a : b);
        }

        void cite(Method method, int number) {
            mark(method, Use.CITED);
            citations.computeIfAbsent(method, m -> new ArrayList<>()).add(number);
        }

        void rank(int rank) {
            if (rank > 0 && (bestRank == 0 || rank < bestRank)) {
                bestRank = rank;
            }
        }

        EvidenceRow toEvidence() {
            Map<Method, Mark> marks = new EnumMap<>(Method.class);
            uses.forEach((method, use) -> marks.put(method, new Mark(use, citations.get(method),
                    method == Method.VECTOR ? bestRank : 0)));
            return new EvidenceRow(id, kind, documentName, LocalContextAssembler.excerpt(text), marks);
        }
    }

    private List<EvidenceRow> evidence(String corpusId, List<MethodRun> runs) {
        Map<String, Row> unitRows = new LinkedHashMap<>();
        Map<String, Row> chunkRows = new LinkedHashMap<>();
        int[] seen = {0};

        for (MethodRun run : runs) {
            if (run.method() == Method.VECTOR) {
                continue;
            }
            for (RetrievalStep step : run.steps()) {
                if (step.kind() == RetrievalStep.Kind.TEXT_UNIT) {
                    Row row = unitRow(corpusId, step.identifier(), null, unitRows, seen);
                    if (row != null) {
                        row.mark(run.method(), Use.IN_CONTEXT);
                    }
                }
            }
            List<Citation> citations = run.citations();
            for (int i = 0; i < citations.size(); i++) {
                Row row = unitRow(corpusId, citations.get(i).textUnitId(), citations.get(i), unitRows, seen);
                if (row != null) {
                    row.cite(run.method(), i + 1);
                }
            }
        }

        runs.stream().filter(run -> run.method() == Method.VECTOR).findFirst()
                .ifPresent(vector -> markVector(corpusId, vector, unitRows, chunkRows, seen));

        List<Row> rows = new ArrayList<>(unitRows.values());
        rows.addAll(chunkRows.values());
        return rows.stream()
                .map(row -> Map.entry(row, row.toEvidence()))
                .sorted(Comparator.<Map.Entry<Row, EvidenceRow>>comparingInt(e -> -e.getValue().citedBy())
                        .thenComparingInt(e -> -e.getValue().inContextOf())
                        .thenComparingInt(e -> e.getKey().firstSeen))
                .map(Map.Entry::getValue)
                .toList();
    }

    /** The row of Text Unit {@code id}, created on first use; null when the unit cannot be found. */
    private Row unitRow(String corpusId, String id, Citation citation, Map<String, Row> rows, int[] seen) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Row existing = rows.get(id);
        if (existing != null) {
            return existing;
        }
        Row row = LocalContextAssembler.loadTextUnit(graphStorePort, corpusId, id)
                .filter(unit -> unit.text() != null)
                .map(unit -> new Row(id, PassageKind.TEXT_UNIT, unit.documentName(), unit.text(), seen[0]++))
                .orElseGet(() -> citation == null ? null
                        : new Row(id, PassageKind.TEXT_UNIT, citation.documentName(), citation.excerpt(),
                                seen[0]++));
        if (row != null) {
            rows.put(id, row);
        }
        return row;
    }

    /**
     * Vector Search's marks: every ranked chunk (used ones in the context,
     * the rest below the cut-off) and every cited chunk, on the Text Unit
     * rows that contain it, or on a row of its own.
     */
    private void markVector(String corpusId, MethodRun vector, Map<String, Row> unitRows,
                            Map<String, Row> chunkRows, int[] seen) {
        Map<String, Chunk> chunks = storedChunks(corpusId);
        for (RankedChunk ranked : vector.ranking()) {
            Use use = ranked.used() ? Use.IN_CONTEXT : Use.RANKED_BELOW_CUTOFF;
            for (Row row : vectorRows(chunks.get(ranked.chunkId()), ranked.chunkId(), ranked.documentName(),
                    ranked.excerpt(), unitRows, chunkRows, seen)) {
                row.mark(Method.VECTOR, use);
                row.rank(ranked.rank());
            }
        }
        // Without a ranking (an older result shape), the VECTOR_CHUNK steps are the context.
        if (vector.ranking().isEmpty()) {
            for (RetrievalStep step : vector.steps()) {
                if (step.kind() == RetrievalStep.Kind.VECTOR_CHUNK) {
                    Chunk chunk = chunks.get(step.identifier());
                    for (Row row : vectorRows(chunk, step.identifier(), chunk == null ? "" : chunk.documentName(),
                            chunk == null ? "" : chunk.text(), unitRows, chunkRows, seen)) {
                        row.mark(Method.VECTOR, Use.IN_CONTEXT);
                    }
                }
            }
        }
        List<Citation> citations = vector.citations();
        for (int i = 0; i < citations.size(); i++) {
            Citation citation = citations.get(i);
            for (Row row : vectorRows(chunks.get(citation.textUnitId()), citation.textUnitId(),
                    citation.documentName(), citation.excerpt(), unitRows, chunkRows, seen)) {
                row.cite(Method.VECTOR, i + 1);
            }
        }
    }

    /** The Text Unit rows containing {@code chunk}, or the chunk's own row (created on first use). */
    private static List<Row> vectorRows(Chunk chunk, String chunkId, String documentName, String fallbackText,
                                        Map<String, Row> unitRows, Map<String, Row> chunkRows, int[] seen) {
        if (chunkId == null || chunkId.isBlank()) {
            return List.of();
        }
        if (chunk != null) {
            String chunkText = CompareAnswers.normalize(chunk.text());
            if (!chunkText.isEmpty()) {
                List<Row> containing = unitRows.values().stream()
                        .filter(row -> sameDocument(chunk.documentName(), row.documentName)
                                && CompareAnswers.normalize(row.text).contains(chunkText))
                        .toList();
                if (!containing.isEmpty()) {
                    return containing;
                }
            }
        }
        return List.of(chunkRows.computeIfAbsent(chunkId, id -> new Row(id, PassageKind.CHUNK,
                chunk != null ? chunk.documentName() : documentName,
                chunk != null ? chunk.text() : fallbackText, seen[0]++)));
    }

    private Map<String, Chunk> storedChunks(String corpusId) {
        Map<String, Chunk> byId = new LinkedHashMap<>();
        Collection<EmbeddedChunk> stored = vectorStorePort.chunks(corpusId);
        if (stored != null) {
            for (EmbeddedChunk embedded : stored) {
                if (embedded != null && embedded.chunk() != null) {
                    byId.putIfAbsent(embedded.chunk().id(), embedded.chunk());
                }
            }
        }
        return byId;
    }

    private static boolean sameDocument(String chunkDocument, String unitDocument) {
        String chunk = nullToEmpty(chunkDocument);
        return chunk.isEmpty() || chunk.equals(nullToEmpty(unitDocument));
    }

    // ---- summary ----

    /**
     * Facts only, no judgement of correctness: which methods answered, the
     * fastest and slowest run, and how much of the cited evidence the
     * methods share.
     */
    static String summary(List<MethodRun> runs, List<EvidenceRow> evidence) {
        List<String> sentences = new ArrayList<>();

        List<String> answered = names(runs, Outcome.ANSWERED);
        List<String> noAnswer = names(runs, Outcome.NO_ANSWER);
        List<String> failed = names(runs, Outcome.FAILED);
        StringBuilder outcomes = new StringBuilder();
        outcomes.append(answered.size()).append(" of ").append(runs.size()).append(" methods answered");
        if (!noAnswer.isEmpty()) {
            outcomes.append("; no answer from ").append(join(noAnswer));
        }
        if (!failed.isEmpty()) {
            outcomes.append("; ").append(join(failed)).append(" failed");
        }
        sentences.add(outcomes.append('.').toString());

        List<MethodRun> timed = runs.stream().filter(run -> run.outcome() != Outcome.FAILED).toList();
        if (timed.size() > 1) {
            MethodRun fastest = timed.stream().min(Comparator.comparingLong(run -> run.timing().totalMs()))
                    .orElseThrow();
            MethodRun slowest = timed.stream().max(Comparator.comparingLong(run -> run.timing().totalMs()))
                    .orElseThrow();
            sentences.add(String.format(Locale.ROOT, "Fastest: %s (%d ms); slowest: %s (%d ms).",
                    fastest.method().label(), fastest.timing().totalMs(),
                    slowest.method().label(), slowest.timing().totalMs()));
        }

        long cited = evidence.stream().filter(row -> row.citedBy() > 0).count();
        long citedByAll = evidence.stream().filter(row -> row.citedBy() == answered.size()).count();
        long citedByOne = evidence.stream().filter(row -> row.citedBy() == 1).count();
        if (cited == 0) {
            sentences.add("No answer cites a passage.");
        } else if (answered.size() > 1) {
            sentences.add(cited + (cited == 1 ? " passage is" : " passages are") + " cited; "
                    + citedByAll + " by every method that answered, " + citedByOne + " by only one.");
        } else {
            sentences.add(cited + (cited == 1 ? " passage is" : " passages are") + " cited.");
        }
        return String.join(" ", sentences);
    }

    private static List<String> names(List<MethodRun> runs, Outcome outcome) {
        return runs.stream().filter(run -> run.outcome() == outcome).map(run -> run.method().label()).toList();
    }

    private static String join(List<String> names) {
        if (names.size() <= 1) {
            return String.join("", names);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
