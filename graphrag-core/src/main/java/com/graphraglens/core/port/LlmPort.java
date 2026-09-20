package com.graphraglens.core.port;

import com.graphraglens.core.domain.Community;
import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.GraphExtraction;

import java.util.Collection;
import java.util.List;

/**
 * Port for LLM-driven knowledge-graph construction and related generation.
 */
public interface LlmPort {

    GraphExtraction extract(Corpus corpus);

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

    default GraphExtraction extractEntitiesAndRelationships(Corpus corpus) {
        return extract(corpus);
    }
}
