package dev.rabauer.graphrag.web;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.usecase.TextUnitProgress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The entity, relationship and community payload shapes. They are the SSE
 * payloads of live ingestion ({@link CorpusIngestController}) and, with the
 * same field names, the bulk graph read ({@link CorpusGraphController}).
 */
final class CorpusEventPayloads {

    record SourceLabel(String documentName, int ordinal) {
    }

    private CorpusEventPayloads() {
    }

    static Map<String, Object> textUnitEventPayload(TextUnitProgress progress) {
        return Map.of(
                "index", progress.index(),
                "total", progress.total(),
                "documentName", progress.documentName() == null ? "" : progress.documentName());
    }

    static Map<String, Object> entityEventPayload(Entity entity, Function<String, SourceLabel> sourceLabelLookup) {
        return Map.of(
                "identity", entity.normalizedIdentity(),
                "name", entity.name(),
                "type", entity.type(),
                "description", entity.description(),
                "sources", sourcePayloads(entity, sourceLabelLookup));
    }

    static Map<String, Object> entityRetypedEventPayload(String previousIdentity, Entity entity,
                                                         Function<String, SourceLabel> sourceLabelLookup) {
        Map<String, Object> payload = new LinkedHashMap<>(entityEventPayload(entity, sourceLabelLookup));
        payload.put("previousIdentity", previousIdentity);
        return payload;
    }

    private static List<Map<String, Object>> sourcePayloads(Entity entity,
                                                            Function<String, SourceLabel> sourceLabelLookup) {
        if (entity.sourceTextUnitIds().isEmpty()) {
            return List.of();
        }
        return entity.sourceTextUnitIds().stream()
                .map(textUnitId -> {
                    SourceLabel sourceLabel = sourceLabelLookup.apply(textUnitId);
                    if (sourceLabel == null) {
                        return null;
                    }
                    return Map.<String, Object>of(
                            "textUnitId", textUnitId,
                            "documentName", sourceLabel.documentName() == null ? "" : sourceLabel.documentName(),
                            "ordinal", sourceLabel.ordinal());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    static Map<String, Object> relationshipEventPayload(Relationship relationship) {
        return Map.of(
                "sourceIdentity", Entity.identityOf(relationship.source(), relationship.sourceType()),
                "source", relationship.source(),
                "targetIdentity", Entity.identityOf(relationship.target(), relationship.targetType()),
                "target", relationship.target(),
                "type", relationship.type(),
                "description", relationship.description());
    }

    static Map<String, Object> communityEventPayload(Community community, List<String> memberEntityIdentities) {
        return Map.of(
                "communityId", community.id(),
                "title", community.title(),
                "summary", community.summary(),
                "memberEntityIdentities", memberEntityIdentities);
    }
}
