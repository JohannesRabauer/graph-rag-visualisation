package dev.rabauer.graphrag.core.domain;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A single entity node in the knowledge graph, including its concise
 * description and the Text Units where it was mentioned.
 *
 * <p>{@code type} is a free-form string: the text pipeline restricts it to
 * its own list (see {@code EntityTypes}), while an imported graph may use any
 * vocabulary (for example {@code Class}, {@code Interface}, {@code Method}).
 *
 * @param name              the display name; for code typically the
 *                          qualified name (e.g. {@code com.acme.OrderService#placeOrder(Order)})
 * @param type              the free-form Entity type
 * @param description       a concise description; may be empty
 * @param sourceTextUnitIds the Text Units mentioning or containing this Entity
 * @param attributes        free-form, string-valued facts (for example
 *                          {@code kind}, {@code module}, {@code package},
 *                          {@code visibility}); never null, sorted by key
 * @param locator           where the Entity is defined; null when unknown
 */
public record Entity(String name, String type, String description, List<String> sourceTextUnitIds,
                     Map<String, String> attributes, SourceLocator locator) {

    public Entity(String name, String type) {
        this(name, type, "", List.of());
    }

    /** An Entity without attributes and locator. */
    public Entity(String name, String type, String description, List<String> sourceTextUnitIds) {
        this(name, type, description, sourceTextUnitIds, Map.of(), null);
    }

    public Entity {
        name = name == null ? "" : name.trim();
        type = type == null ? "Unknown" : type.trim();
        description = description == null ? "" : description.trim();
        sourceTextUnitIds = sourceTextUnitIds == null ? List.of() : List.copyOf(sourceTextUnitIds);
        attributes = Attributes.normalize(attributes);
    }

    public String normalizedIdentity() {
        return name.toLowerCase(Locale.ROOT) + "::" + type.toLowerCase(Locale.ROOT);
    }

    /** The value of one attribute, if present. */
    public Optional<String> attribute(String key) {
        return Optional.ofNullable(attributes.get(key));
    }

    /** This Entity with {@code attributes} instead of its own. */
    public Entity withAttributes(Map<String, String> attributes) {
        return new Entity(name, type, description, sourceTextUnitIds, attributes, locator);
    }

    /** This Entity with {@code locator} instead of its own. */
    public Entity withLocator(SourceLocator locator) {
        return new Entity(name, type, description, sourceTextUnitIds, attributes, locator);
    }

    /**
     * This Entity under another name, type, description and Text Units,
     * keeping its attributes and locator.
     */
    public Entity with(String name, String type, String description, List<String> sourceTextUnitIds) {
        return new Entity(name, type, description, sourceTextUnitIds, attributes, locator);
    }

    /**
     * Shorthand for {@code new Entity(name, type).normalizedIdentity()} —
     * used wherever only a Relationship's source/target name+type (not a
     * full Entity) is on hand and an identity string is needed to match it
     * against a rendered node (e.g. {@code CorpusController}'s
     * relationship payloads).
     */
    public static String identityOf(String name, String type) {
        return new Entity(name, type).normalizedIdentity();
    }
}
