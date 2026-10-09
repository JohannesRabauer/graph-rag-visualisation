package dev.rabauer.graphrag.core.port;

import dev.rabauer.graphrag.core.domain.Chunk;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityPoint;
import dev.rabauer.graphrag.core.domain.CommunityStats;
import dev.rabauer.graphrag.core.domain.CommunitySummary;
import dev.rabauer.graphrag.core.domain.ComparisonFacts;
import dev.rabauer.graphrag.core.domain.ComparisonVerdict;
import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.SynthesizedAnswer;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.domain.UploadedDocument;

import java.util.Collection;
import java.util.List;

/**
 * Port for LLM-driven knowledge-graph construction and related generation.
 *
 * <p>Every capability is optional. {@link #extract(Corpus)} is the only
 * method without a default, and only so that {@code LlmPort} stays a
 * functional interface (existing callers implement it as a lambda). A port
 * that never extracts returns {@link GraphExtraction#empty()} there and
 * reports {@link #extractsEntities()} {@code false}; {@link #none()} is such
 * a port with every capability off. All other methods have documented,
 * deterministic defaults, so a use case given a port without a capability
 * degrades to the same deterministic result it produces without any port.
 *
 * <p>Capability flags, in the style of {@link #synthesizesAnswers()}:
 * <ul>
 *   <li>{@link #extractsEntities()} (default {@code true}): whether
 *       {@link #extract(TextUnit, List)} really extracts. When {@code false},
 *       {@code ExtractEntitiesAndRelationships} makes no extraction call and
 *       persists only the Text Units; the import path
 *       ({@code ImportKnowledgeGraph}) never calls extraction at all.</li>
 *   <li>{@link #summarizesCommunities()} (default {@code false}): whether
 *       {@link #summarizeCommunity(Collection, Collection)} calls a model.
 *       {@code DetectCommunities} always calls the port (an override is
 *       honoured either way); the flag only enables its bounded parallelism,
 *       which is pointless for deterministic summaries.</li>
 *   <li>{@link #derivesSubQuestions()} (default {@code false}): whether
 *       {@link #deriveDriftSubQuestions(String, Collection)} calls a model.
 *       DRIFT uses the deterministic derivation when the port's call fails
 *       and failures are isolated per item.</li>
 *   <li>{@link #synthesizesAnswers()} (default {@code false}): whether the
 *       {@code Answer*} use cases generate an answer. The retrieval-only use
 *       cases never synthesize.</li>
 * </ul>
 */
public interface LlmPort {

    /**
     * Extracts a knowledge graph from a corpus.
     *
     * <p>The only abstract method (so a lambda is a valid {@code LlmPort}).
     * A port without extraction returns {@link GraphExtraction#empty()} and
     * overrides {@link #extractsEntities()} to return {@code false}.
     *
     * @param corpus the corpus to extract a knowledge graph from; never null
     * @return the extracted entities and relationships for {@code corpus};
     *         never null
     */
    GraphExtraction extract(Corpus corpus);

    /**
     * A port with every capability off: no extraction, the deterministic
     * Community summaries and DRIFT sub-questions, no answer synthesis.
     * Use it for exact, pre-built graphs (see {@code ImportKnowledgeGraph})
     * when no model is available at all.
     */
    static LlmPort none() {
        return NoLlm.INSTANCE;
    }

    /**
     * Whether {@link #extract(TextUnit, List)} really extracts Entities and
     * Relationships. Defaults to {@code true} for compatibility: a port
     * implemented as a lambda extracts.
     */
    default boolean extractsEntities() {
        return true;
    }

    /**
     * Whether {@link #summarizeCommunity(Collection, Collection)} calls a
     * model (and so may be slow, fail, or benefit from parallelism). The
     * default is {@code false}: the inherited summaries are deterministic.
     */
    default boolean summarizesCommunities() {
        return false;
    }

    /**
     * Whether {@link #deriveDriftSubQuestions(String, Collection)} calls a
     * model. The default is {@code false}: one deterministic sub-question per
     * candidate Community.
     */
    default boolean derivesSubQuestions() {
        return false;
    }

    /**
     * Extracts a knowledge graph from a single Text Unit (AD-24), restricted
     * to the given Entity types.
     *
     * <p>The default wraps the unit as a one-document Corpus and delegates to
     * {@link #extract(Corpus)}, so {@code LlmPort} stays usable as a lambda;
     * real adapters override this to prompt with {@code entityTypes}. Callers
     * still normalise the returned types themselves.
     *
     * @param unit        the passage to extract from; never null
     * @param entityTypes the allowed Entity types, in order; never null
     * @return the entities and relationships found in {@code unit}; never null
     */
    default GraphExtraction extract(TextUnit unit, List<String> entityTypes) {
        return extract(new Corpus(unit.corpusId(),
                List.of(new UploadedDocument(unit.documentName(), unit.text()))));
    }

    /**
     * Whether {@link #mapCommunities(String, List)} calls a model. When true
     * and {@link #synthesizesAnswers()} is true too, {@code AnswerGlobalSearch}
     * answers by map-reduce over the corpus's Communities instead of from the
     * three best-matching ones. The default is {@code false}.
     */
    default boolean mapsCommunities() {
        return false;
    }

    /**
     * The map step of map-reduce Global Search: the key points each of
     * {@code communities} contributes to {@code question}, each scored from 0
     * to 100. A Community that does not help contributes no point (or one
     * scored 0). Only called when {@link #mapsCommunities()} is true; the
     * default returns no points.
     *
     * @param question    the user's question
     * @param communities one batch of Communities, in reading order; never null
     * @return the points, each naming a Community of the batch; never null
     */
    default List<CommunityPoint> mapCommunities(String question, List<Community> communities) {
        return List.of();
    }

    /**
     * Whether {@link #summarizeDescription(String, String)} calls a model.
     * When true, {@code ExtractEntitiesAndRelationships} keeps every distinct
     * description sentence of a run and has an Entity's or Relationship's
     * description summarised once, at the end of the run, when it outgrew
     * {@code GraphElementMerger.DESCRIPTION_LIMIT}. The default is
     * {@code false}: later sentences that do not fit are dropped.
     */
    default boolean summarizesDescriptions() {
        return false;
    }

    /**
     * One description of an Entity or Relationship from the merged
     * descriptions of all its sightings, short enough for
     * {@code GraphElementMerger.DESCRIPTION_LIMIT}. Only called when
     * {@link #summarizesDescriptions()} is true; the caller cuts a longer
     * reply. The default returns {@code description} unchanged.
     *
     * @param elementName the Entity's name, or {@code source -[type]-> target}
     *                    for a Relationship
     * @param description the merged descriptions, one sentence after another
     * @return the summary; never null
     */
    default String summarizeDescription(String elementName, String description) {
        return description == null ? "" : description;
    }

    /**
     * {@link #extract(TextUnit, List)} told the names of the Entities found
     * earlier in the same extraction run, most-mentioned first, so a model
     * can reuse them instead of inventing spelling variants.
     *
     * <p>The default ignores the names and delegates to
     * {@link #extract(TextUnit, List)}, so existing ports and lambdas keep
     * working.
     *
     * @param unit             the passage to extract from; never null
     * @param entityTypes      the allowed Entity types, in order; never null
     * @param knownEntityNames names already found in this run, capped by the
     *                         caller; never null, empty for the first unit
     * @return the entities and relationships found in {@code unit}; never null
     */
    default GraphExtraction extract(TextUnit unit, List<String> entityTypes, List<String> knownEntityNames) {
        return extract(unit, entityTypes);
    }

    /**
     * A one-sentence Community summary. The default is deterministic:
     * "This community centers on" plus up to four distinct member names.
     */
    default String summarizeCommunity(Collection<Entity> members) {
        if (members == null || members.isEmpty()) {
            return "A small connected cluster of related entities.";
        }

        String names = members.stream()
                .map(Entity::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .limit(4)
                .reduce((left, right) -> left + ", " + right)
                .orElse("related entities");

        return "This community centers on " + names + ".";
    }

    /**
     * Writes a Community's title and summary from its members (with
     * descriptions) and its internal Relationships (both endpoints are
     * members, with descriptions). Callers cap both collections.
     *
     * <p>The default wraps {@link #summarizeCommunity(Collection)} with the
     * deterministic title from {@link CommunitySummary#deterministicTitle(Collection)},
     * so existing implementations and lambdas keep working.
     *
     * @param members       the Community's members, in entity order; never null
     * @param relationships the internal Relationships, highest weight first; never null
     * @return the title and summary; never null
     */
    default CommunitySummary summarizeCommunity(Collection<Entity> members, Collection<Relationship> relationships) {
        return new CommunitySummary(CommunitySummary.deterministicTitle(members), summarizeCommunity(members));
    }

    /**
     * {@link #summarizeCommunity(Collection, Collection)} with the size and
     * composition of the whole Community: {@code members} is capped (and may
     * be ordered by the caller), {@code stats} describes all of it: how many
     * members it has and how they split by module or package.
     * {@code DetectCommunities} calls this overload.
     *
     * <p>The default ignores {@code stats} and calls the two-argument method,
     * so existing implementations and lambdas keep working. A port that wraps
     * another must forward this overload too, as {@code StageClock} does.
     *
     * @param stats the Community's size and attribute counts; never null
     */
    default CommunitySummary summarizeCommunity(Collection<Entity> members, Collection<Relationship> relationships,
                                                CommunityStats stats) {
        return summarizeCommunity(members, relationships);
    }

    /**
     * The DRIFT sub-questions, one per candidate Community in candidate order.
     * The default is deterministic: the question followed by
     * {@code "Community summary: "} and the Community's summary.
     */
    default List<String> deriveDriftSubQuestions(String question, Collection<Community> communities) {
        if (communities == null || communities.isEmpty()) {
            return List.of();
        }

        String normalizedQuestion = question == null ? "" : question.trim();
        return communities.stream()
                .filter(community -> community != null)
                .map(community -> {
                    String summary = community.summary() == null ? "" : community.summary().trim();
                    if (normalizedQuestion.isBlank()) {
                        return "Community summary: " + summary;
                    }
                    return normalizedQuestion + " Community summary: " + summary;
                })
                .toList();
    }

    /**
     * Synthesizes a plain-language answer from retrieved text chunks. Used by
     * the Vector Search baseline only when {@link #synthesizesAnswers()} is
     * false; a synthesizing port answers from the chunks through
     * {@link #synthesizeAnswer(String, List)} instead.
     *
     * <p>The default implementation concatenates chunk texts with a separator
     * and wraps them with a lead-in sentence. Real LLM implementations may
     * override this to call the model directly.
     *
     * @param question the original question
     * @param chunks   the retrieved chunks to synthesize from, in retrieval order
     * @return a synthesized answer string; never null
     */
    default String synthesizeFromChunks(String question, List<Chunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "No relevant chunks were found for this question.";
        }
        String combined = chunks.stream()
                .map(Chunk::text)
                .reduce((a, b) -> a + "\n---\n" + b)
                .orElse("");
        return "Based on the retrieved text passages: " + combined;
    }

    /**
     * Whether {@link #synthesizeAnswer(String, List)} really generates a
     * Local Search answer (Story 15.2). The default is false, so offline
     * stubs and lambdas keep the deterministic templated answer.
     */
    default boolean synthesizesAnswers() {
        return false;
    }

    /**
     * Writes an answer from a numbered, bounded context, citing items inline
     * as {@code [n]} (Story 15.2) — for the GraphRAG modes and, with each
     * retrieved chunk as a {@code TEXT_UNIT} item, for the Vector Search
     * baseline. Only called when
     * {@link #synthesizesAnswers()} is true.
     *
     * @param question the user's question
     * @param context  the context items, numbered {@code 1..n} in order; never null
     * @return the raw answer (citations unresolved), or null by default
     */
    default SynthesizedAnswer synthesizeAnswer(String question, List<ContextItem> context) {
        return null;
    }

    /**
     * Writes a short verdict on where a GraphRAG answer and a Vector Search
     * answer to the same question differ, and why, grounded in the measured
     * {@code facts}.
     *
     * <p>The default is the deterministic {@link ComparisonVerdict#ruleBased(ComparisonFacts)}
     * summary ({@link ComparisonVerdict.Source#RULE}), so offline stubs and
     * lambdas need no model. Real adapters may override this with one short
     * model call ({@link ComparisonVerdict.Source#LLM}); callers fall back to
     * the rule text when that call fails.
     *
     * @param question     the user's question
     * @param graphAnswer  the GraphRAG answer text, or its no-answer reason
     * @param vectorAnswer the Vector Search answer text, or its no-answer reason
     * @param facts        both sides' key figures and the passage overlap; never null
     * @return the verdict; never null
     */
    default ComparisonVerdict compareAnswers(String question, String graphAnswer, String vectorAnswer,
                                             ComparisonFacts facts) {
        return ComparisonVerdict.ruleBased(facts);
    }

    default GraphExtraction extractEntitiesAndRelationships(Corpus corpus) {
        return extract(corpus);
    }

    /** The port behind {@link #none()}. */
    final class NoLlm implements LlmPort {

        static final NoLlm INSTANCE = new NoLlm();

        private NoLlm() {
        }

        @Override
        public GraphExtraction extract(Corpus corpus) {
            return GraphExtraction.empty();
        }

        @Override
        public GraphExtraction extract(TextUnit unit, List<String> entityTypes) {
            return GraphExtraction.empty();
        }

        @Override
        public boolean extractsEntities() {
            return false;
        }
    }
}
