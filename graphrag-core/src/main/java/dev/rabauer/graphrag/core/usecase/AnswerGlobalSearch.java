package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Citation;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.CommunityPoint;
import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.SynthesizedAnswer;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphReadPort;
import dev.rabauer.graphrag.core.port.LlmPort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Answers a Global Search question by aggregating already-persisted
 * Community summaries.
 *
 * <p>This use case only reads {@link GraphReadPort#communities(String)} — it
 * never triggers {@link DetectCommunities} or summary generation itself
 * (AD-6). Communities are scored against the question the same
 * keyword-overlap way Local Search already scores sentences, so the demo
 * stays deterministic and provider-agnostic.
 *
 * <p>Global Search reads only Communities tied to the selected corpus.
 *
 * <p>With a semantic {@link EmbeddingPort}, the {@value #SEMANTIC_COMMUNITY_COUNT}
 * Communities closest in meaning to the question are recorded instead (most
 * similar first) and the answer comes from the first one. When the corpus has
 * no Community embeddings, the keyword path above is used for that query.
 *
 * <p>Story 15.3: with an answer-synthesizing {@link LlmPort}
 * ({@link LlmPort#synthesizesAnswers()}), the answer is generated instead of
 * templated, from the top {@value #SYNTHESIS_COMMUNITY_COUNT} Communities
 * (semantic, or by keyword score) and, after each, up to
 * {@value #MAX_TEXT_UNITS_PER_COMMUNITY} of its members' highest-weight Text
 * Units, every item recorded as a trace step as it is added. The model's
 * inline {@code [n]} citations are resolved by {@link CitationResolver}.
 * Without such a port the templated path runs exactly as before.
 *
 * <p>Map-reduce: when the port also maps Communities
 * ({@link LlmPort#mapsCommunities()}), the answer draws on the whole corpus
 * instead. Up to {@value #MAX_MAPPED_COMMUNITIES} Communities (most similar
 * first with a semantic {@link EmbeddingPort}, otherwise by keyword score,
 * then id) are read in batches of {@value #MAP_BATCH_SIZE}, each recorded as
 * a {@code COMMUNITY} step, and the port returns their key points scored
 * 0-100 ({@link LlmPort#mapCommunities}). The {@value #MAX_REDUCE_POINTS}
 * best points above 0, then up to {@value #MAX_TEXT_UNITS_PER_COMMUNITY}
 * member Text Units of each of the first {@value #MAX_PASSAGE_COMMUNITIES}
 * Communities they come from, form the context of the one synthesis call
 * (the reduce step). No point above 0 means the corpus does not answer.
 */
public class AnswerGlobalSearch {

    /** How many Communities the semantic path records as trace steps. */
    static final int SEMANTIC_COMMUNITY_COUNT = 3;
    /** Story 15.3: Communities that enter the synthesis context. */
    static final int SYNTHESIS_COMMUNITY_COUNT = 3;
    /** Story 15.3: member Text Units that enter the synthesis context after each Community. */
    static final int MAX_TEXT_UNITS_PER_COMMUNITY = 2;
    /** Map-reduce: how many Communities are read at most. */
    static final int MAX_MAPPED_COMMUNITIES = 30;
    /** Map-reduce: how many Communities one map call reads. */
    static final int MAP_BATCH_SIZE = 5;
    /** Map-reduce: how many of the best key points enter the reduce context. */
    static final int MAX_REDUCE_POINTS = 20;
    /** Map-reduce: from how many of the points' Communities member passages are added. */
    static final int MAX_PASSAGE_COMMUNITIES = 5;
    static final String NOT_IN_CONTEXT_REASON =
            "The Community summaries and passages retrieved for this question do not answer it. "
                    + "Try asking about a named person, place, or event.";

    private final GraphReadPort graphStorePort;
    private final EmbeddingPort embeddingPort;
    private final LlmPort llmPort;

    public AnswerGlobalSearch(GraphReadPort graphStorePort) {
        this(graphStorePort, null, null);
    }

    public AnswerGlobalSearch(GraphReadPort graphStorePort, EmbeddingPort embeddingPort) {
        this(graphStorePort, embeddingPort, null);
    }

    /**
     * @param llmPort when {@link LlmPort#synthesizesAnswers()} (Story 15.3), the
     *                answer is generated from a cited context; null or a
     *                non-synthesizing port keeps the templated answer
     */
    public AnswerGlobalSearch(GraphReadPort graphStorePort, EmbeddingPort embeddingPort, LlmPort llmPort) {
        this.graphStorePort = graphStorePort;
        this.embeddingPort = embeddingPort;
        this.llmPort = llmPort;
    }

    public GlobalSearchAnswer answer(String question) {
        return answer(question, null);
    }

    public GlobalSearchAnswer answer(String question, String corpusId) {
        Collection<Community> communities = graphStorePort.communities(corpusId);
        if (communities == null || communities.isEmpty()) {
            return GlobalSearchAnswer.noCommunitiesYet();
        }
        if (llmPort != null && llmPort.synthesizesAnswers() && llmPort.mapsCommunities()) {
            return mapReduceAnswer(question, corpusId, communities);
        }

        List<Community> similar = similarCommunities(graphStorePort, embeddingPort, question, corpusId,
                SEMANTIC_COMMUNITY_COUNT);
        if (llmPort != null && llmPort.synthesizesAnswers()) {
            List<Community> top = similar.isEmpty()
                    ? topByKeyword(communities, KeywordMatcher.tokenize(question))
                    : similar.stream().limit(SYNTHESIS_COMMUNITY_COUNT).toList();
            if (!top.isEmpty()) {
                return synthesizedAnswer(question, corpusId, top);
            }
            // No candidate: the keyword path below gives the existing no-match answer.
        } else if (!similar.isEmpty()) {
            List<RetrievalStep> semanticSteps = new ArrayList<>();
            for (Community community : similar) {
                semanticSteps.add(new RetrievalStep(RetrievalStep.Kind.COMMUNITY, community.id(), community.summary()));
            }
            return GlobalSearchAnswer.matched(
                    "Across the corpus, the strongest signal is that " + similar.getFirst().summary(), semanticSteps);
        }

        Set<String> tokens = KeywordMatcher.tokenize(question);
        Community best = null;
        int bestScore = -1;
        List<RetrievalStep> steps = new ArrayList<>();
        for (Community community : communities) {
            steps.add(new RetrievalStep(RetrievalStep.Kind.COMMUNITY, community.id(), community.summary()));
            int score = KeywordMatcher.score(community.summary(), tokens);
            if (score > bestScore
                    || (score == bestScore && best != null && community.id().compareTo(best.id()) < 0)) {
                bestScore = score;
                best = community;
            }
        }

        if (best != null && bestScore > 0) {
            return GlobalSearchAnswer.matched(
                    "Across the corpus, the strongest signal is that " + best.summary(), steps);
        }

        return GlobalSearchAnswer.matched(
                "The corpus has Community summaries, but none of them clearly match that question yet. "
                        + "Try asking about a named person, place, or event.",
                steps);
    }

    /**
     * Story 15.3: the top {@value #SYNTHESIS_COMMUNITY_COUNT} Communities with a
     * keyword score above zero, highest score first, id as tiebreak.
     */
    private static List<Community> topByKeyword(Collection<Community> communities, Set<String> tokens) {
        record Scored(Community community, int score) {
        }
        return communities.stream()
                .filter(Objects::nonNull)
                .map(community -> new Scored(community, KeywordMatcher.score(community.summary(), tokens)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(Scored::score).reversed()
                        .thenComparing(scored -> scored.community().id()))
                .limit(SYNTHESIS_COMMUNITY_COUNT)
                .map(Scored::community)
                .toList();
    }

    /**
     * Story 15.3: records each Community and, directly after it, up to
     * {@value #MAX_TEXT_UNITS_PER_COMMUNITY} member Text Units not already
     * added, asks the LLM, and resolves its citations.
     */
    private GlobalSearchAnswer synthesizedAnswer(String question, String corpusId, List<Community> top) {
        Members members = Members.load(graphStorePort, corpusId, top.stream().map(Community::id).toList());
        List<RetrievalStep> steps = new ArrayList<>();
        List<LocalContextAssembler.Item> items = new ArrayList<>();
        Map<String, Citation> citationsByUnit = new LinkedHashMap<>();
        for (Community community : top) {
            steps.add(new RetrievalStep(RetrievalStep.Kind.COMMUNITY, community.id(), community.summary()));
            items.add(new LocalContextAssembler.Item("COMMUNITY:" + community.id(), RetrievalStep.Kind.COMMUNITY,
                    communityText(community), null));
            addMemberTextUnits(corpusId, community, members, steps, items, citationsByUnit);
        }
        return synthesize(question, steps, items, citationsByUnit);
    }

    /**
     * Map-reduce: reads the Communities batch by batch for scored key points
     * (map), then answers from the best points and their Communities' member
     * passages in one synthesis call (reduce).
     */
    private GlobalSearchAnswer mapReduceAnswer(String question, String corpusId, Collection<Community> communities) {
        List<Community> mapped = similarCommunities(graphStorePort, embeddingPort, question, corpusId,
                MAX_MAPPED_COMMUNITIES);
        if (mapped.isEmpty()) {
            mapped = byKeywordScore(communities, KeywordMatcher.tokenize(question)).stream()
                    .limit(MAX_MAPPED_COMMUNITIES).toList();
        }
        List<RetrievalStep> steps = new ArrayList<>();
        Map<String, Community> byId = new LinkedHashMap<>();
        List<CommunityPoint> points = new ArrayList<>();
        for (int start = 0; start < mapped.size(); start += MAP_BATCH_SIZE) {
            List<Community> batch = mapped.subList(start, Math.min(start + MAP_BATCH_SIZE, mapped.size()));
            Set<String> batchIds = new LinkedHashSet<>();
            for (Community community : batch) {
                steps.add(new RetrievalStep(RetrievalStep.Kind.COMMUNITY, community.id(), community.summary()));
                byId.put(community.id(), community);
                batchIds.add(community.id());
            }
            for (CommunityPoint point : LocalContextAssembler.orEmpty(llmPort.mapCommunities(question, batch))) {
                if (point != null && point.score() > 0 && !point.text().isEmpty()
                        && batchIds.contains(point.communityId())) {
                    points.add(point);
                }
            }
        }
        // List.sort is stable, so equal scores keep reading order.
        points.sort(Comparator.comparingInt(CommunityPoint::score).reversed());
        List<CommunityPoint> best = points.stream().limit(MAX_REDUCE_POINTS).toList();
        if (best.isEmpty()) {
            return GlobalSearchAnswer.notInContext(NOT_IN_CONTEXT_REASON, steps);
        }

        List<LocalContextAssembler.Item> items = new ArrayList<>();
        Set<String> pointCommunities = new LinkedHashSet<>();
        for (int i = 0; i < best.size(); i++) {
            CommunityPoint point = best.get(i);
            Community community = byId.get(point.communityId());
            String name = community.title().isEmpty() ? community.id() : community.title();
            items.add(new LocalContextAssembler.Item("POINT:" + i, RetrievalStep.Kind.COMMUNITY,
                    name + ": " + point.text() + " (importance " + point.score() + ")", null));
            pointCommunities.add(point.communityId());
        }
        Members members = Members.load(graphStorePort, corpusId,
                pointCommunities.stream().limit(MAX_PASSAGE_COMMUNITIES).toList());
        Map<String, Citation> citationsByUnit = new LinkedHashMap<>();
        pointCommunities.stream().limit(MAX_PASSAGE_COMMUNITIES).forEach(communityId -> addMemberTextUnits(
                corpusId, byId.get(communityId), members, steps, items, citationsByUnit));
        return synthesize(question, steps, items, citationsByUnit);
    }

    /** Adds up to {@value #MAX_TEXT_UNITS_PER_COMMUNITY} of the Community's best member Text Units not yet added. */
    private void addMemberTextUnits(String corpusId, Community community, Members members,
                                    List<RetrievalStep> steps, List<LocalContextAssembler.Item> items,
                                    Map<String, Citation> citationsByUnit) {
        Set<String> memberIdentities = members.byCommunity().getOrDefault(community.id(), Set.of());
        int added = 0;
        for (String unitId : rankedMemberUnits(memberIdentities, members.entityByIdentity(),
                members.relationships())) {
            if (added >= MAX_TEXT_UNITS_PER_COMMUNITY) {
                break;
            }
            if (citationsByUnit.containsKey(unitId)) {
                continue;
            }
            if (LocalContextAssembler.addTextUnit(graphStorePort, corpusId, unitId, steps, items,
                    citationsByUnit) != null) {
                added++;
            }
        }
    }

    /** Numbers the items, asks the LLM, and resolves its citations. */
    private GlobalSearchAnswer synthesize(String question, List<RetrievalStep> steps,
                                          List<LocalContextAssembler.Item> items,
                                          Map<String, Citation> citationsByUnit) {
        List<ContextItem> context = LocalContextAssembler.number(items);
        SynthesizedAnswer synthesized = llmPort.synthesizeAnswer(question, context);
        if (LocalContextAssembler.isNotInContext(synthesized)) {
            return GlobalSearchAnswer.notInContext(NOT_IN_CONTEXT_REASON, steps);
        }
        CitationResolver.Resolution resolution =
                CitationResolver.resolve(synthesized.text(), context, citationsByUnit);
        if (resolution.text().isBlank()) {
            return GlobalSearchAnswer.notInContext(NOT_IN_CONTEXT_REASON, steps);
        }
        return GlobalSearchAnswer.synthesized(resolution.text(), steps, resolution.citations());
    }

    /** Every Community, highest keyword score first, id as tiebreak. */
    private static List<Community> byKeywordScore(Collection<Community> communities, Set<String> tokens) {
        return communities.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt((Community community) -> KeywordMatcher.score(community.summary(),
                        tokens)).reversed().thenComparing(Community::id))
                .toList();
    }

    /**
     * The member identities per Community, and the Entities and Relationships of the members of
     * the Communities that are used, read by identity (the store is not asked for its whole corpus).
     */
    private record Members(Map<String, Set<String>> byCommunity, Map<String, Entity> entityByIdentity,
                           Collection<Relationship> relationships) {

        static Members load(GraphReadPort graph, String corpusId, Collection<String> communityIds) {
            Map<String, Set<String>> byCommunity = new HashMap<>();
            for (CommunityMembership membership : LocalContextAssembler.orEmpty(
                    graph.communityMemberships(corpusId))) {
                if (membership != null) {
                    byCommunity.computeIfAbsent(membership.communityId(), ignored -> new LinkedHashSet<>())
                            .add(membership.entityIdentity());
                }
            }
            Set<String> wanted = new LinkedHashSet<>();
            for (String communityId : communityIds) {
                wanted.addAll(byCommunity.getOrDefault(communityId, Set.of()));
            }
            Map<String, Entity> entityByIdentity = new HashMap<>();
            for (Entity entity : LocalContextAssembler.orEmpty(graph.entities(corpusId, wanted))) {
                if (entity != null) {
                    entityByIdentity.putIfAbsent(entity.normalizedIdentity(), entity);
                }
            }
            return new Members(byCommunity, entityByIdentity,
                    LocalContextAssembler.orEmpty(graph.relationshipsTouching(corpusId, wanted)));
        }
    }

    /**
     * The Text Units the Community's members cite, ranked by the summed weight
     * of internal Relationships (both endpoints members) citing each, plus 1
     * per member Entity citing it; ties keep first-seen order (members first).
     */
    static List<String> rankedMemberUnits(Set<String> members, Map<String, Entity> entityByIdentity,
                                                  Collection<Relationship> relationships) {
        Map<String, Integer> scoreByUnit = new LinkedHashMap<>();
        for (String identity : members) {
            Entity entity = entityByIdentity.get(identity);
            if (entity == null) {
                continue;
            }
            for (String unitId : entity.sourceTextUnitIds()) {
                scoreByUnit.merge(unitId, 1, Integer::sum);
            }
        }
        for (Relationship relationship : relationships) {
            if (relationship == null
                    || !members.contains(Entity.identityOf(relationship.source(), relationship.sourceType()))
                    || !members.contains(Entity.identityOf(relationship.target(), relationship.targetType()))) {
                continue;
            }
            for (String unitId : relationship.sourceTextUnitIds()) {
                scoreByUnit.merge(unitId, relationship.weight(), Integer::sum);
            }
        }
        List<String> ranked = new ArrayList<>(scoreByUnit.keySet());
        // List.sort is stable, so equal scores keep first-seen order.
        ranked.sort(Comparator.comparingInt((String unitId) -> scoreByUnit.get(unitId)).reversed());
        return ranked;
    }

    /** The prompt text for a Community: "title: summary", or the summary alone without a title. */
    static String communityText(Community community) {
        return community.title().isEmpty() ? community.summary() : community.title() + ": " + community.summary();
    }

    /**
     * The top {@code k} Communities by meaning, most similar first; empty
     * without a semantic port or when the corpus has no embedded Communities.
     */
    static List<Community> similarCommunities(GraphReadPort graphStorePort, EmbeddingPort embeddingPort,
                                              String question, String corpusId, int k) {
        if (!EmbedGraphElements.isSemantic(embeddingPort)) {
            return List.of();
        }
        List<Community> similar;
        try {
            similar = graphStorePort.similarCommunities(
                    corpusId, embeddingPort.embed(question == null ? "" : question), k);
        } catch (RuntimeException e) {
            throw new SemanticMatchingException("Semantic Community matching failed: " + e.getMessage(), e);
        }
        if (similar == null) {
            return List.of();
        }
        return similar.stream().filter(Objects::nonNull).limit(k).toList();
    }
}
