package dev.rabauer.graphrag.adapter.langchain4j;

import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.GraphExtraction;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.TextUnit;
import dev.rabauer.graphrag.core.domain.UploadedDocument;
import dev.rabauer.graphrag.core.port.LlmPort;

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
            extractFromText(document.content(), entitiesByIdentity, relationships);
        }

        return new GraphExtraction(new ArrayList<>(entitiesByIdentity.values()), relationships);
    }

    /**
     * Story 13.1: the same deterministic sentence/regex extraction, run over a
     * single Text Unit's passage. The stub only ever infers {@code Person} or
     * {@code Concept}, both on the fixed list, so {@code entityTypes} needs no
     * further handling here.
     */
    @Override
    public GraphExtraction extract(TextUnit unit, List<String> entityTypes) {
        if (unit == null || unit.text() == null || unit.text().isBlank()) {
            return new GraphExtraction(List.of(), List.of());
        }
        Map<String, Entity> entitiesByIdentity = new LinkedHashMap<>();
        List<Relationship> relationships = new ArrayList<>();
        extractFromText(unit.text(), entitiesByIdentity, relationships);
        return new GraphExtraction(new ArrayList<>(entitiesByIdentity.values()), relationships);
    }

    private void extractFromText(String text, Map<String, Entity> entitiesByIdentity, List<Relationship> relationships) {
        String[] sentences = text.split("(?<=[.!?])\\s+");
        for (String sentence : sentences) {
            List<String> names = extractNames(sentence);
            for (String name : names) {
                Entity entity = new Entity(name, inferType(name), sentenceDescription(sentence), List.of());
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
        String description = sentenceDescription(sentence);

        if (lowered.contains(" met ")) {
            return new Relationship(source, inferType(source), "met", target, inferType(target), description, List.of(), 1);
        }
        if (lowered.contains(" visited ")) {
            return new Relationship(source, inferType(source), "visited", target, inferType(target), description, List.of(), 1);
        }
        if (lowered.contains(" helped ")) {
            return new Relationship(source, inferType(source), "helped", target, inferType(target), description, List.of(), 1);
        }
        if (lowered.contains(" chased ")) {
            return new Relationship(source, inferType(source), "chased", target, inferType(target), description, List.of(), 1);
        }
        if (lowered.contains(" told ")) {
            return new Relationship(source, inferType(source), "told", target, inferType(target), description, List.of(), 1);
        }
        return new Relationship(source, inferType(source), "related_to", target, inferType(target), description, List.of(), 1);
    }

    private String sentenceDescription(String sentence) {
        return sentence == null ? "" : sentence.trim();
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
