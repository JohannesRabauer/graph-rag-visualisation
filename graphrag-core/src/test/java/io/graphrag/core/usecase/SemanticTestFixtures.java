package io.graphrag.core.usecase;

import io.graphrag.core.domain.Community;
import io.graphrag.core.domain.CommunityMembership;
import io.graphrag.core.domain.ContextItem;
import io.graphrag.core.domain.Corpus;
import io.graphrag.core.domain.Entity;
import io.graphrag.core.domain.GraphExtraction;
import io.graphrag.core.domain.Relationship;
import io.graphrag.core.domain.SynthesizedAnswer;
import io.graphrag.core.domain.TextUnit;
import io.graphrag.core.port.EmbeddingPort;
import io.graphrag.core.port.GraphStorePort;
import io.graphrag.core.port.LlmPort;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Shared fakes for the semantic-seed tests: an {@link EmbeddingPort} with
 * hand-made vectors per text, and a corpus-scoped {@link GraphStorePort} that
 * answers {@code similar*} by exact cosine over the embeddings persisted into
 * it (the way the Neo4j vector index does).
 */
final class SemanticTestFixtures {

    private SemanticTestFixtures() {
    }

    /** Maps known texts to fixed vectors; anything else embeds to the zero-ish fallback. */
    static final class FakeEmbeddingPort implements EmbeddingPort {

        private final Map<String, float[]> vectors = new HashMap<>();
        private final boolean semantic;
        final List<String> embedded = new ArrayList<>();

        FakeEmbeddingPort() {
            this(true);
        }

        FakeEmbeddingPort(boolean semantic) {
            this.semantic = semantic;
        }

        FakeEmbeddingPort map(String text, float... vector) {
            vectors.put(text, vector);
            return this;
        }

        @Override
        public float[] embed(String text) {
            embedded.add(text);
            float[] vector = vectors.get(text);
            return vector != null ? vector : new float[] {0.01f, 0.01f, 0.01f, 0.01f};
        }

        @Override
        public boolean isSemantic() {
            return semantic;
        }
    }

    static final class FakeGraphStore implements GraphStorePort {

        final Map<String, List<Entity>> entitiesByCorpus = new LinkedHashMap<>();
        final Map<String, List<Relationship>> relationshipsByCorpus = new LinkedHashMap<>();
        final Map<String, List<Community>> communitiesByCorpus = new LinkedHashMap<>();
        final Map<String, List<TextUnit>> textUnitsByCorpus = new LinkedHashMap<>();
        final Map<String, List<CommunityMembership>> membershipsByCorpus = new LinkedHashMap<>();
        final Map<String, Map<String, float[]>> entityEmbeddingsByCorpus = new HashMap<>();
        final Map<String, Map<String, float[]>> communityEmbeddingsByCorpus = new HashMap<>();

        FakeGraphStore entities(String corpusId, Entity... entities) {
            entitiesByCorpus.computeIfAbsent(corpusId, ignored -> new ArrayList<>()).addAll(List.of(entities));
            return this;
        }

        FakeGraphStore relationships(String corpusId, Relationship... relationships) {
            relationshipsByCorpus.computeIfAbsent(corpusId, ignored -> new ArrayList<>())
                    .addAll(List.of(relationships));
            return this;
        }

        FakeGraphStore communities(String corpusId, Community... communities) {
            communitiesByCorpus.computeIfAbsent(corpusId, ignored -> new ArrayList<>()).addAll(List.of(communities));
            return this;
        }

        FakeGraphStore textUnits(String corpusId, TextUnit... textUnits) {
            textUnitsByCorpus.computeIfAbsent(corpusId, ignored -> new ArrayList<>()).addAll(List.of(textUnits));
            return this;
        }

        FakeGraphStore memberships(String corpusId, String communityId, String... entityIdentities) {
            List<CommunityMembership> memberships =
                    membershipsByCorpus.computeIfAbsent(corpusId, ignored -> new ArrayList<>());
            for (String identity : entityIdentities) {
                memberships.add(new CommunityMembership(communityId, identity));
            }
            return this;
        }

        @Override
        public Collection<CommunityMembership> communityMemberships(String corpusId) {
            return membershipsByCorpus.getOrDefault(corpusId, List.of());
        }

        @Override
        public Collection<TextUnit> textUnits(String corpusId) {
            return textUnitsByCorpus.getOrDefault(corpusId, List.of());
        }

        @Override
        public void persistEntities(Collection<Entity> entities) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void persistRelationships(Collection<Relationship> relationships) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Collection<Entity> entities(String corpusId) {
            return entitiesByCorpus.getOrDefault(corpusId, List.of());
        }

        @Override
        public Collection<Relationship> relationships(String corpusId) {
            return relationshipsByCorpus.getOrDefault(corpusId, List.of());
        }

        @Override
        public Collection<Community> communities(String corpusId) {
            return communitiesByCorpus.getOrDefault(corpusId, List.of());
        }

        @Override
        public void persistEntityEmbeddings(String corpusId, Map<String, float[]> byIdentity) {
            entityEmbeddingsByCorpus.computeIfAbsent(corpusId, ignored -> new HashMap<>()).putAll(byIdentity);
        }

        @Override
        public void persistCommunityEmbeddings(String corpusId, Map<String, float[]> byCommunityId) {
            communityEmbeddingsByCorpus.computeIfAbsent(corpusId, ignored -> new HashMap<>()).putAll(byCommunityId);
        }

        @Override
        public List<Entity> similarEntities(String corpusId, float[] query, int k) {
            Map<String, float[]> embeddings = entityEmbeddingsByCorpus.getOrDefault(corpusId, Map.of());
            return entities(corpusId).stream()
                    .filter(entity -> embeddings.containsKey(entity.normalizedIdentity()))
                    .sorted(Comparator.comparingDouble(
                            (Entity entity) -> cosine(query, embeddings.get(entity.normalizedIdentity()))).reversed())
                    .limit(k)
                    .toList();
        }

        @Override
        public List<Community> similarCommunities(String corpusId, float[] query, int k) {
            Map<String, float[]> embeddings = communityEmbeddingsByCorpus.getOrDefault(corpusId, Map.of());
            return communities(corpusId).stream()
                    .filter(community -> embeddings.containsKey(community.id()))
                    .sorted(Comparator.comparingDouble(
                            (Community community) -> cosine(query, embeddings.get(community.id()))).reversed())
                    .limit(k)
                    .toList();
        }

        private static double cosine(float[] left, float[] right) {
            double dot = 0;
            double leftNorm = 0;
            double rightNorm = 0;
            for (int i = 0; i < Math.min(left.length, right.length); i++) {
                dot += left[i] * right[i];
                leftNorm += left[i] * left[i];
                rightNorm += right[i] * right[i];
            }
            return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
        }
    }

    /**
     * Story 15.3: an {@link LlmPort} that records each synthesis context and
     * answers with {@code answer.apply(context)}; optional fixed sub-questions.
     */
    static final class RecordingLlmPort implements LlmPort {
        private final boolean synthesizes;
        private final Function<List<ContextItem>, SynthesizedAnswer> answer;
        private List<String> subQuestions;
        final List<List<ContextItem>> contexts = new ArrayList<>();

        RecordingLlmPort(Function<List<ContextItem>, SynthesizedAnswer> answer) {
            this(true, answer);
        }

        RecordingLlmPort(boolean synthesizes, Function<List<ContextItem>, SynthesizedAnswer> answer) {
            this.synthesizes = synthesizes;
            this.answer = answer;
        }

        RecordingLlmPort subQuestions(String... subQuestions) {
            this.subQuestions = List.of(subQuestions);
            return this;
        }

        @Override
        public GraphExtraction extract(Corpus corpus) {
            return new GraphExtraction(List.of(), List.of());
        }

        @Override
        public List<String> deriveDriftSubQuestions(String question, Collection<Community> communities) {
            return subQuestions != null ? subQuestions : LlmPort.super.deriveDriftSubQuestions(question, communities);
        }

        @Override
        public boolean synthesizesAnswers() {
            return synthesizes;
        }

        @Override
        public SynthesizedAnswer synthesizeAnswer(String question, List<ContextItem> context) {
            contexts.add(context);
            return answer.apply(context);
        }
    }
}
