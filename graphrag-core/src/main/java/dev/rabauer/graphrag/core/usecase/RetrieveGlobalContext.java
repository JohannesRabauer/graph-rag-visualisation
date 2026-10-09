package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.RetrievalTrace;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphReadPort;
import dev.rabauer.graphrag.core.retrieval.GlobalRetrievalOptions;
import dev.rabauer.graphrag.core.retrieval.RetrievalResult;
import dev.rabauer.graphrag.core.retrieval.RetrievedItem;
import dev.rabauer.graphrag.core.retrieval.SeedMatch;
import dev.rabauer.graphrag.core.retrieval.SeedMatcher;
import dev.rabauer.graphrag.core.retrieval.SeedMatchers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Retrieval-only Global Search: picks the Communities that best match the
 * question (see {@link GlobalRetrievalOptions}) and returns, per Community, a
 * {@code COMMUNITY} item, its best member Entities and its most relevant
 * member Text Units, with the full trace — no answer is synthesized.
 */
public class RetrieveGlobalContext {

    static final String NO_COMMUNITIES_REASON =
            "This corpus has no Communities. Detect Communities (or import them) first, or use Local retrieval.";
    static final String NO_MATCH_REASON =
            "No Community summary or member matched the question.";

    private final GraphReadPort graph;
    private final EmbeddingPort embeddingPort;
    private final SeedMatcher memberMatcher;
    private final GlobalRetrievalOptions options;

    public RetrieveGlobalContext(GraphReadPort graph) {
        this(graph, null, null, GlobalRetrievalOptions.defaults());
    }

    public RetrieveGlobalContext(GraphReadPort graph, EmbeddingPort embeddingPort) {
        this(graph, embeddingPort, null, GlobalRetrievalOptions.defaults());
    }

    /**
     * @param embeddingPort semantic: Communities are picked by meaning; null or
     *                      non-semantic: by keywords and member matches
     * @param memberMatcher scores members against the question (null means
     *                      {@link SeedMatchers#defaultFor(EmbeddingPort)}
     *                      without embeddings, i.e. keywords)
     */
    public RetrieveGlobalContext(GraphReadPort graph, EmbeddingPort embeddingPort, SeedMatcher memberMatcher,
                                 GlobalRetrievalOptions options) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.embeddingPort = embeddingPort;
        this.memberMatcher = memberMatcher == null ? SeedMatchers.defaultFor(null) : memberMatcher;
        this.options = options == null ? GlobalRetrievalOptions.defaults() : options;
    }

    public RetrievalResult retrieve(String question, String corpusId) {
        return retrieve(question, corpusId, options);
    }

    public RetrievalResult retrieve(String question, String corpusId, GlobalRetrievalOptions options) {
        GlobalRetrievalOptions effective = options == null ? this.options : options;
        CommunityContext context = CommunityContext.load(graph, corpusId);
        if (context.communities().isEmpty()) {
            return RetrievalResult.empty(RetrievalResult.Mode.GLOBAL, question, corpusId,
                    RetrievalResult.Status.NO_COMMUNITIES, NO_COMMUNITIES_REASON, List.of(), List.of());
        }
        Map<String, Double> memberScores = new HashMap<>();
        List<Scored> candidates = candidates(graph, embeddingPort, memberMatcher, question, corpusId, effective,
                context, memberScores);
        if (candidates.isEmpty()) {
            return RetrievalResult.empty(RetrievalResult.Mode.GLOBAL, question, corpusId,
                    RetrievalResult.Status.NO_MATCH, NO_MATCH_REASON, List.of(), List.of());
        }

        List<LocalExpansion.Touch> touches = new ArrayList<>();
        Set<String> addedUnits = new HashSet<>();
        for (Scored candidate : candidates) {
            touches.add(communityTouch(candidate.community(), candidate.score()));
            Set<String> members = context.membersOf(candidate.community().id());
            List<Entity> ranked = rankedMembers(members, context, memberScores);
            for (Entity member : ranked.subList(0, Math.min(effective.memberEntitiesPerCommunity(), ranked.size()))) {
                touches.add(LocalExpansion.entityTouch(member, memberScores.getOrDefault(
                        member.normalizedIdentity(), 0.0), 0));
            }
            int added = 0;
            for (String unitId : AnswerGlobalSearch.rankedMemberUnits(members, context.entityByIdentity(),
                    context.relationships())) {
                if (added >= effective.textUnitsPerCommunity()) {
                    break;
                }
                if (addedUnits.contains(unitId)) {
                    continue;
                }
                LocalExpansion.Touch unit = LocalExpansion.textUnitTouch(graph, corpusId, unitId, 0);
                if (unit != null) {
                    addedUnits.add(unitId);
                    touches.add(unit);
                    added++;
                }
            }
        }

        List<RetrievedItem> items = new ArrayList<>();
        List<RetrievalStep> steps = new ArrayList<>();
        for (LocalExpansion.Touch touch : touches) {
            items.add(touch.toRetrievedItem(items.size() + 1));
            steps.add(touch.step());
        }
        return new RetrievalResult(RetrievalResult.Mode.GLOBAL, question, corpusId, RetrievalResult.Status.MATCHED,
                "", items, new RetrievalTrace("", steps), List.of());
    }

    /** A candidate Community and its score. */
    record Scored(Community community, double score) {
    }

    /**
     * The candidate Communities: semantic (score {@code 1/rank}) when the port
     * is semantic and the corpus has Community embeddings, else the keyword
     * overlap with {@code title + summary} plus the member seed scores. Both
     * parts are scaled to [0, 1] by the best Community's value (so a fused
     * matcher's scores of about 0.03 count as much as an identifier matcher's
     * 100 or the integer keyword counts), and a Community scores their sum;
     * above zero only, highest first, id as tiebreak, at most
     * {@code maxCommunities}. Fills {@code memberScores} with the raw member
     * seed scores it used.
     */
    static List<Scored> candidates(GraphReadPort graph, EmbeddingPort embeddingPort, SeedMatcher memberMatcher,
                                   String question, String corpusId, GlobalRetrievalOptions options,
                                   CommunityContext context, Map<String, Double> memberScores) {
        List<Community> similar = AnswerGlobalSearch.similarCommunities(graph, embeddingPort, question, corpusId,
                options.maxCommunities());
        if (!similar.isEmpty()) {
            List<Scored> scored = new ArrayList<>();
            for (Community community : similar) {
                scored.add(new Scored(community, 1.0 / (scored.size() + 1)));
            }
            return scored;
        }
        if (options.matchMembers()) {
            for (SeedMatch match : memberMatcher.match(question, corpusId, graph, options.memberSeedLimit())) {
                memberScores.merge(match.entity().normalizedIdentity(), match.score(), Math::max);
            }
        }
        Set<String> tokens = KeywordMatcher.tokenize(question);
        // Keyword counts (integers) and member seed scores (any scale: 100 for identifiers, ~0.03 when fused)
        // are each scaled by their best Community, so both parts are in [0, 1] and add up fairly.
        double[] keywordScores = new double[context.communities().size()];
        double[] memberTotals = new double[keywordScores.length];
        double bestKeyword = 0;
        double bestMember = 0;
        for (int i = 0; i < keywordScores.length; i++) {
            Community community = context.communities().get(i);
            keywordScores[i] = KeywordMatcher.score(community.title() + " " + community.summary(), tokens);
            for (String member : context.membersOf(community.id())) {
                memberTotals[i] += memberScores.getOrDefault(member, 0.0);
            }
            bestKeyword = Math.max(bestKeyword, keywordScores[i]);
            bestMember = Math.max(bestMember, memberTotals[i]);
        }
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < keywordScores.length; i++) {
            double score = (bestKeyword > 0 ? keywordScores[i] / bestKeyword : 0)
                    + (bestMember > 0 ? memberTotals[i] / bestMember : 0);
            if (score > 0) {
                scored.add(new Scored(context.communities().get(i), score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(candidate -> candidate.community().id()));
        return List.copyOf(scored.subList(0, Math.min(options.maxCommunities(), scored.size())));
    }

    /** Members by seed score, then by the weight of their Relationships inside the Community, then stored order. */
    private static List<Entity> rankedMembers(Set<String> members, CommunityContext context,
                                              Map<String, Double> memberScores) {
        Map<String, Integer> internalWeight = new HashMap<>();
        for (Relationship relationship : context.relationships()) {
            if (members.contains(relationship.sourceIdentity()) && members.contains(relationship.targetIdentity())) {
                internalWeight.merge(relationship.sourceIdentity(), relationship.weight(), Integer::sum);
                internalWeight.merge(relationship.targetIdentity(), relationship.weight(), Integer::sum);
            }
        }
        List<Entity> ranked = new ArrayList<>();
        for (String identity : members) {
            Entity entity = context.entityByIdentity().get(identity);
            if (entity != null) {
                ranked.add(entity);
            }
        }
        ranked.sort(Comparator.comparingDouble((Entity entity) -> memberScores.getOrDefault(
                        entity.normalizedIdentity(), 0.0)).reversed()
                .thenComparing(Comparator.comparingInt((Entity entity) -> internalWeight.getOrDefault(
                        entity.normalizedIdentity(), 0)).reversed()));
        return ranked;
    }

    static LocalExpansion.Touch communityTouch(Community community, double score) {
        RetrievalStep step = new RetrievalStep(RetrievalStep.Kind.COMMUNITY, community.id(),
                community.title().isEmpty() ? community.summary() : community.title(), null, community.attributes());
        String text = AnswerGlobalSearch.communityText(community);
        return new LocalExpansion.Touch(step, new LocalContextAssembler.Item("COMMUNITY:" + community.id(),
                RetrievalStep.Kind.COMMUNITY, text, null), text, score, 0, null);
    }

    /** The corpus's Communities, memberships, Entities and Relationships, read once. */
    record CommunityContext(List<Community> communities, Map<String, Set<String>> membersByCommunity,
                            Map<String, Entity> entityByIdentity, List<Relationship> relationships) {

        static CommunityContext load(GraphReadPort graph, String corpusId) {
            List<Community> communities = nonNull(graph.communities(corpusId));
            if (communities.isEmpty()) {
                return new CommunityContext(List.of(), Map.of(), Map.of(), List.of());
            }
            Map<String, Set<String>> members = new LinkedHashMap<>();
            for (CommunityMembership membership : nonNull(graph.communityMemberships(corpusId))) {
                members.computeIfAbsent(membership.communityId(), ignored -> new LinkedHashSet<>())
                        .add(membership.entityIdentity());
            }
            Map<String, Entity> entities = new LinkedHashMap<>();
            for (Entity entity : nonNull(graph.entities(corpusId))) {
                entities.putIfAbsent(entity.normalizedIdentity(), entity);
            }
            return new CommunityContext(communities, members, entities, nonNull(graph.relationships(corpusId)));
        }

        Set<String> membersOf(String communityId) {
            return membersByCommunity.getOrDefault(communityId, Set.of());
        }

        private static <T> List<T> nonNull(Collection<T> values) {
            return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
        }
    }
}
