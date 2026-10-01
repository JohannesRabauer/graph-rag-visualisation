package io.graphrag.core.port;

import io.graphrag.core.domain.Chunk;
import io.graphrag.core.domain.Community;
import io.graphrag.core.domain.CommunitySummary;
import io.graphrag.core.domain.Corpus;
import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.GraphExtraction;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.TextUnit;
import io.graphrag.core.domain.UploadedDocument;

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
     * Synthesizes a plain-language answer from retrieved text chunks.
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

    default GraphExtraction extractEntitiesAndRelationships(Corpus corpus) {
        return extract(corpus);
    }
}
