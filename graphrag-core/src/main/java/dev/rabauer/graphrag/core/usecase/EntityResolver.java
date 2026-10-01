package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministically resolves entity spelling and type variants within one
 * extraction run.
 */
public final class EntityResolver {

    private final Map<String, NameState> states = new LinkedHashMap<>();
    private int typeOrder;

    public ResolvedEntity resolve(Entity entity) {
        NameState state = stateFor(entity.name(), entity.type());
        String previousIdentity = state.currentIdentity();
        state.countMention(entity.type());
        state.recomputeType();
        Entity resolved = new Entity(state.canonicalName, state.currentType,
                entity.description(), entity.sourceTextUnitIds());
        return new ResolvedEntity(resolved,
                previousIdentity != null && !previousIdentity.equals(resolved.normalizedIdentity())
                        ? Optional.of(previousIdentity)
                        : Optional.empty());
    }

    public Relationship resolve(Relationship relationship) {
        Endpoint source = resolveEndpoint(relationship.source(), relationship.sourceType());
        Endpoint target = resolveEndpoint(relationship.target(), relationship.targetType());
        return new Relationship(source.name(), source.type(), relationship.type(), target.name(), target.type(),
                relationship.description(), relationship.sourceTextUnitIds(), relationship.weight());
    }

    public static String nameKey(String name) {
        String raw = name == null ? "" : name;
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replaceAll("^[\\p{P}\\p{S}]+|[\\p{P}\\p{S}]+$", "")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty()) {
            return normalized;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private Endpoint resolveEndpoint(String name, String type) {
        NameState state = stateFor(name, type);
        return new Endpoint(state.canonicalName, state.currentType);
    }

    private NameState stateFor(String name, String type) {
        String key = nameKey(name);
        return states.computeIfAbsent(key, ignored -> new NameState(trimmedName(name), normalizeType(type)));
    }

    private static String trimmedName(String name) {
        return name == null ? "" : name.trim();
    }

    private static String normalizeType(String type) {
        return type == null ? "Unknown" : type.trim();
    }

    public record ResolvedEntity(Entity entity, Optional<String> previousIdentity) {
    }

    private record Endpoint(String name, String type) {
    }

    private final class NameState {
        private final String canonicalName;
        private final Map<String, TypeState> types = new LinkedHashMap<>();
        private String currentType;

        private NameState(String canonicalName, String initialType) {
            this.canonicalName = canonicalName;
            this.currentType = initialType;
            types.put(initialType, new TypeState(0, typeOrder++));
        }

        private void countMention(String type) {
            TypeState existing = types.computeIfAbsent(normalizeType(type), ignored -> new TypeState(0, typeOrder++));
            existing.mentions++;
        }

        private void recomputeType() {
            TypeState winner = null;
            String winnerType = currentType;
            for (Map.Entry<String, TypeState> entry : types.entrySet()) {
                TypeState candidate = entry.getValue();
                if (winner == null
                        || candidate.mentions > winner.mentions
                        || (candidate.mentions == winner.mentions && candidate.order < winner.order)) {
                    winner = candidate;
                    winnerType = entry.getKey();
                }
            }
            currentType = winnerType;
        }

        private String currentIdentity() {
            return currentType == null ? null : Entity.identityOf(canonicalName, currentType);
        }
    }

    private static final class TypeState {
        private int mentions;
        private final int order;

        private TypeState(int mentions, int order) {
            this.mentions = mentions;
            this.order = order;
        }
    }
}
