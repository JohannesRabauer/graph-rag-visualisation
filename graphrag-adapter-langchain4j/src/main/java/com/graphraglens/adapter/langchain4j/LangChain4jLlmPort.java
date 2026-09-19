package com.graphraglens.adapter.langchain4j;

import com.graphraglens.core.domain.Corpus;
import com.graphraglens.core.domain.Entity;
import com.graphraglens.core.domain.GraphExtraction;
import com.graphraglens.core.domain.Relationship;
import com.graphraglens.core.domain.UploadedDocument;
import com.graphraglens.core.port.LlmPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight, deterministic LLM adapter stub for Story 2.4.
 */
public class LangChain4jLlmPort implements LlmPort {

    private static final Pattern NAME_PATTERN = Pattern.compile("(?:^|\\s)([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,2})");

    @Override
    public String summarizeCommunity(java.util.Collection<Entity> members) {
        if (members == null || members.isEmpty()) {
            return "A small connected cluster of related entities.";
        }

        List<String> names = members.stream()
                .map(Entity::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .limit(4)
                .toList();

        if (names.isEmpty()) {
            return "A small connected cluster of related entities.";
        }

        String joined = String.join(", ", names);
        return "This community centers on " + joined + ".";
    }

    @Override
    public GraphExtraction extract(Corpus corpus) {
        if (corpus == null || corpus.documents() == null || corpus.documents().isEmpty()) {
            return new GraphExtraction(List.of(), List.of());
        }

        Map<String, Entity> entitiesByIdentity = new LinkedHashMap<>();
        List<Relationship> relationships = new ArrayList<>();

        for (UploadedDocument document : corpus.documents()) {
            if (document == null || document.content() == null || document.content().isBlank()) {
                continue;
            }

            String[] sentences = document.content().split("(?<=[.!?])\\s+");
            for (String sentence : sentences) {
                List<String> names = extractNames(sentence);
                for (String name : names) {
                    Entity entity = new Entity(name, inferType(name));
                    entitiesByIdentity.putIfAbsent(entity.normalizedIdentity(), entity);
                }

                if (names.size() >= 2) {
                    Relationship relationship = inferRelationship(sentence, names);
                    if (relationship != null) {
                        relationships.add(relationship);
                    }
                }
            }
        }

        return new GraphExtraction(new ArrayList<>(entitiesByIdentity.values()), relationships);
    }

    private List<String> extractNames(String sentence) {
        List<String> names = new ArrayList<>();
        Matcher matcher = NAME_PATTERN.matcher(sentence);
        while (matcher.find()) {
            String raw = matcher.group(1).trim();
            if ((raw.contains(" ") || raw.length() > 1) && !raw.equals("The") && !raw.equals("A") && !raw.equals("An")) {
                names.add(raw);
            }
        }
        return deduplicate(names);
    }

    private List<String> deduplicate(List<String> names) {
        List<String> unique = new ArrayList<>();
        for (String name : names) {
            String normalized = name.trim();
            if (!unique.contains(normalized)) {
                unique.add(normalized);
            }
        }
        return unique;
    }

    private Relationship inferRelationship(String sentence, List<String> names) {
        String lowered = sentence.toLowerCase(Locale.ROOT);
        String source = names.getFirst();
        String target = names.getLast();

        if (lowered.contains(" met ")) {
            return new Relationship(source, inferType(source), "met", target, inferType(target));
        }
        if (lowered.contains(" visited ")) {
            return new Relationship(source, inferType(source), "visited", target, inferType(target));
        }
        if (lowered.contains(" helped ")) {
            return new Relationship(source, inferType(source), "helped", target, inferType(target));
        }
        if (lowered.contains(" chased ")) {
            return new Relationship(source, inferType(source), "chased", target, inferType(target));
        }
        if (lowered.contains(" told ")) {
            return new Relationship(source, inferType(source), "told", target, inferType(target));
        }
        return new Relationship(source, inferType(source), "related_to", target, inferType(target));
    }

    private String inferType(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return "Unknown";
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains("holmes") || lower.contains("watson") || lower.contains("morstan")) {
            return "Person";
        }
        return "Concept";
    }
}
