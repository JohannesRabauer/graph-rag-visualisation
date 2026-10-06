package dev.rabauer.graphrag.testkit;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.SourceLocator;
import dev.rabauer.graphrag.core.domain.TextUnit;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The small, fixed graph every port contract runs against: two corpora with
 * unique ids per instance (so a shared database never mixes runs), Entities
 * with and without attributes and locators, weighted Relationships of several
 * types, Text Units, a Community with attributes and its memberships.
 *
 * <p>Corpus {@link #corpusId()}:
 * <pre>
 * com.acme.Alpha (Class)  --DECLARES-->   com.acme.Alpha#run() (Method)
 * com.acme.Alpha#run()    --CALLS(3)-->   com.acme.Beta (Interface)
 * com.acme.Alpha          --IMPLEMENTS--> com.acme.Beta
 * com.acme.Gamma (Class)  --USES(2)-->    com.acme.Alpha        (Gamma: no attributes, no locator)
 * Community "c-core" = { Alpha, Alpha#run(), Beta }
 * </pre>
 * Corpus {@link #otherCorpusId()}: one Entity {@code com.other.Delta}, one
 * Text Unit, nothing else.
 */
public final class ContractGraph {

    public static final String ALPHA = "com.acme.Alpha";
    public static final String RUN = "com.acme.Alpha#run()";
    public static final String BETA = "com.acme.Beta";
    public static final String GAMMA = "com.acme.Gamma";
    public static final String DELTA = "com.other.Delta";
    public static final String COMMUNITY_ID = "c-core";

    public static final SourceLocator ALPHA_AT = SourceLocator.of("src/main/java/com/acme/Alpha.java", 1, 40);
    public static final SourceLocator RUN_AT = SourceLocator.of("src/main/java/com/acme/Alpha.java", 10, 18);
    public static final SourceLocator BETA_AT = SourceLocator.of("src/main/java/com/acme/Beta.java", 1, 6);
    public static final SourceLocator CALL_AT = SourceLocator.of("src/main/java/com/acme/Alpha.java", 14);

    private final String corpusId;
    private final String otherCorpusId;

    private ContractGraph(String suffix) {
        this.corpusId = "contract-a-" + suffix;
        this.otherCorpusId = "contract-b-" + suffix;
    }

    /** A fresh instance with unique corpus ids. */
    public static ContractGraph create() {
        return new ContractGraph(UUID.randomUUID().toString().substring(0, 8));
    }

    public String corpusId() {
        return corpusId;
    }

    public String otherCorpusId() {
        return otherCorpusId;
    }

    public List<TextUnit> textUnits() {
        return List.of(
                new TextUnit("tu-alpha", corpusId, "Alpha.java", 0, "public class Alpha implements Beta { void run() {} }",
                        Map.of("language", "java"), ALPHA_AT),
                new TextUnit("tu-beta", corpusId, "Beta.java", 0, "public interface Beta { void call(); }",
                        Map.of("language", "java"), BETA_AT));
    }

    public List<Entity> entities() {
        return List.of(
                new Entity(ALPHA, "Class", "The alpha class.", List.of("tu-alpha"),
                        Map.of("kind", "class", "module", "core", "visibility", "public"), ALPHA_AT),
                new Entity(RUN, "Method", "Runs alpha.", List.of("tu-alpha"), Map.of("kind", "method"), RUN_AT),
                new Entity(BETA, "Interface", "The beta contract.", List.of("tu-beta"), Map.of("kind", "interface"),
                        BETA_AT),
                new Entity(GAMMA, "Class"));
    }

    public List<Relationship> relationships() {
        return List.of(
                new Relationship(ALPHA, "Class", "DECLARES", RUN, "Method", "", List.of("tu-alpha"), 1, Map.of(),
                        RUN_AT),
                new Relationship(RUN, "Method", "CALLS", BETA, "Interface", "Alpha#run calls Beta.",
                        List.of("tu-alpha"), 3, Map.of("callCount", "3"), CALL_AT),
                new Relationship(ALPHA, "Class", "IMPLEMENTS", BETA, "Interface", "", List.of(), 1, Map.of(), ALPHA_AT),
                new Relationship(GAMMA, "Class", "USES", ALPHA, "Class", "", List.of(), 2, Map.of(), null));
    }

    public List<Community> communities() {
        return List.of(new Community(COMMUNITY_ID, "Core", "Alpha, its run method and the Beta contract.",
                Map.of("contentHash", "h-1")));
    }

    public List<CommunityMembership> memberships() {
        return List.of(
                new CommunityMembership(COMMUNITY_ID, identity(ALPHA, "Class")),
                new CommunityMembership(COMMUNITY_ID, identity(RUN, "Method")),
                new CommunityMembership(COMMUNITY_ID, identity(BETA, "Interface")));
    }

    public List<TextUnit> otherTextUnits() {
        return List.of(new TextUnit("tu-delta", otherCorpusId, "Delta.java", 0, "class Delta {}", Map.of(),
                SourceLocator.of("src/main/java/com/other/Delta.java", 1, 1)));
    }

    public List<Entity> otherEntities() {
        return List.of(new Entity(DELTA, "Class", "", List.of("tu-delta"), Map.of("kind", "class"),
                SourceLocator.of("src/main/java/com/other/Delta.java", 1, 1)));
    }

    /** {@link Entity#identityOf(String, String)}. */
    public static String identity(String name, String type) {
        return Entity.identityOf(name, type);
    }

    /** The fixture Entity with {@code name}. */
    public Entity entity(String name) {
        return entities().stream().filter(entity -> entity.name().equals(name)).findFirst().orElseThrow();
    }
}
