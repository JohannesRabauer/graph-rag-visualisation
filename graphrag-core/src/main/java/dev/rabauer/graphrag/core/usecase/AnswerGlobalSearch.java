package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Citation;
import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.CommunityMembership;
import dev.rabauer.graphrag.core.domain.ContextItem;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.domain.Relationship;
import dev.rabauer.graphrag.core.domain.RetrievalStep;
import dev.rabauer.graphrag.core.domain.SynthesizedAnswer;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphStorePort;
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
 * <p>This use case only reads {@link GraphStorePort#communities(String)} — it
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
 */
public class AnswerGlobalSearch {

    /** How many Communities the semantic path records as trace steps. */
    static final int SEMANTIC_COMMUNITY_COUNT = 3;
    /** Story 15.3: Communities that enter the synthesis context. */
    static final int SYNTHESIS_COMMUNITY_COUNT = 3;
    /** Story 15.3: member Text Units that enter the synthesis context after each Community. */
    static final int MAX_TEXT_UNITS_PER_COMMUNITY = 2;
    static final String NOT_IN_CONTEXT_REASON =
            "The Community summaries and passages retrieved for this question do not answer it. "
                    + "Try asking about a named person, place, or event.";

    private final GraphStorePort graphStorePort;
    private final EmbeddingPort embeddingPort;
    private final LlmPort llmPort;

    public AnswerGlobalSearch(GraphStorePort graphStorePort) {
        this(graphStorePort, null, null);
    }

    public AnswerGlobalSearch(GraphStorePort graphStorePort, EmbeddingPort embeddingPort) {
        this(graphStorePort, embeddingPort, null);
    }

    /**
     * @param llmPort when {@link LlmPort#synthesizesAnswers()} (Story 15.3), the
     *                answer is generated from a cited context; null or a
     *                non-synthesizing port keeps the templated answer
     */
    public AnswerGlobalSearch(GraphStorePort graphStorePort, EmbeddingPort embeddingPort, LlmPort llmPort) {
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
        Map<String, Set<String>> membersByCommunity = new HashMap<>();
        for (CommunityMembership membership : LocalContextAssembler.orEmpty(
                graphStorePort.communityMemberships(corpusId))) {
            if (membership != null) {
                membersByCommunity.computeIfAbsent(membership.communityId(), ignored -> new LinkedHashSet<>())
                        .add(membership.entityIdentity());
            }
        }
        Map<String, Entity> entityByIdentity = new HashMap<>();
        for (Entity entity : LocalContextAssembler.orEmpty(graphStorePort.entities(corpusId))) {
            if (entity != null) {
                entityByIdentity.putIfAbsent(entity.normalizedIdentity(), entity);
            }
        }
        Collection<Relationship> relationships = LocalContextAssembler.orEmpty(graphStorePort.relationships(corpusId));

        List<RetrievalStep> steps = new ArrayList<>();
        List<LocalContextAssembler.Item> items = new ArrayList<>();
        Map<String, Citation> citationsByUnit = new LinkedHashMap<>();
        for (Community community : top) {
            steps.add(new RetrievalStep(RetrievalStep.Kind.COMMUNITY, community.id(), community.summary()));
            items.add(new LocalContextAssembler.Item("COMMUNITY:" + community.id(), RetrievalStep.Kind.COMMUNITY,
                    communityText(community), null));

            Set<String> members = membersByCommunity.getOrDefault(community.id(), Set.of());
            int added = 0;
            for (String unitId : rankedMemberUnits(members, entityByIdentity, relationships)) {
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

    /**
     * The Text Units the Community's members cite, ranked by the summed weight
     * of internal Relationships (both endpoints members) citing each, plus 1
     * per member Entity citing it; ties keep first-seen order (members first).
     */
    private static List<String> rankedMemberUnits(Set<String> members, Map<String, Entity> entityByIdentity,
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
    static List<Community> similarCommunities(GraphStorePort graphStorePort, EmbeddingPort embeddingPort,
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
