package dev.rabauer.graphrag.core.port;

import dev.rabauer.graphrag.core.domain.Chunk;
import dev.rabauer.graphrag.core.domain.Community;
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
 */
public interface LlmPort {

    /**
     * Extracts a knowledge graph from a corpus.
     *
     * @param corpus the corpus to extract a knowledge graph from; never null
     * @return the extracted entities and relationships for {@code corpus};
     *         never null
     */
    GraphExtraction extract(Corpus corpus);

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
}
