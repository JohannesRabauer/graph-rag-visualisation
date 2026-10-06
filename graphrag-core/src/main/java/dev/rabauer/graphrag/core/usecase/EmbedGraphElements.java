package dev.rabauer.graphrag.core.usecase;

import dev.rabauer.graphrag.core.domain.Community;
import dev.rabauer.graphrag.core.domain.Corpus;
import dev.rabauer.graphrag.core.domain.Entity;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphStorePort;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Embeds every persisted Entity and Community of a corpus so the searches can
 * find their starting points by meaning instead of by shared words.
 *
 * <p>Runs after community detection. Only already-persisted text is embedded:
 * {@code name + ": " + description} for an Entity and the {@code summary} for
 * a Community. Nothing happens without a semantic {@link EmbeddingPort}
 * (a null port, or one whose {@link EmbeddingPort#isSemantic()} is
 * {@code false}), so offline ingestion never embeds anything. An embedding
 * failure propagates unchanged; nothing is retried.</p>
 */
public class EmbedGraphElements {

    private final GraphStorePort graphStorePort;
    private final EmbeddingPort embeddingPort;

    public EmbedGraphElements(GraphStorePort graphStorePort, EmbeddingPort embeddingPort) {
        this.graphStorePort = graphStorePort;
        this.embeddingPort = embeddingPort;
    }

    /**
     * @return whether {@code embeddingPort} is a real semantic embedding model
     */
    public static boolean isSemantic(EmbeddingPort embeddingPort) {
        return embeddingPort != null && embeddingPort.isSemantic();
    }

    public void run(Corpus corpus) {
        if (corpus == null) {
            return;
        }
        run(corpus.id());
    }

    /** {@link #run(Corpus)} for a corpus known only by its id (e.g. an imported graph). */
    public void run(String corpusId) {
        if (corpusId == null || !isSemantic(embeddingPort)) {
            return;
        }

        Map<String, float[]> entityEmbeddings = new LinkedHashMap<>();
        for (Entity entity : orEmpty(graphStorePort.entities(corpusId))) {
            if (entity != null) {
                entityEmbeddings.put(entity.normalizedIdentity(), embeddingPort.embed(entityText(entity)));
            }
        }

        Map<String, float[]> communityEmbeddings = new LinkedHashMap<>();
        for (Community community : orEmpty(graphStorePort.communities(corpusId))) {
            if (community != null) {
                communityEmbeddings.put(community.id(), embeddingPort.embed(community.summary()));
            }
        }

        if (!entityEmbeddings.isEmpty()) {
            graphStorePort.persistEntityEmbeddings(corpusId, entityEmbeddings);
        }
        if (!communityEmbeddings.isEmpty()) {
            graphStorePort.persistCommunityEmbeddings(corpusId, communityEmbeddings);
        }
    }

    /** Embeds only the Communities of {@code corpusId}; nothing without a semantic port. */
    public void embedCommunities(String corpusId) {
        if (corpusId == null || !isSemantic(embeddingPort)) {
            return;
        }
        Map<String, float[]> communityEmbeddings = new LinkedHashMap<>();
        for (Community community : orEmpty(graphStorePort.communities(corpusId))) {
            if (community != null) {
                communityEmbeddings.put(community.id(), embeddingPort.embed(community.summary()));
            }
        }
        if (!communityEmbeddings.isEmpty()) {
            graphStorePort.persistCommunityEmbeddings(corpusId, communityEmbeddings);
        }
    }

    /**
     * Embeds only {@code entities} of {@code corpusId} (for example the ones an
     * incremental update imported); nothing without a semantic port.
     */
    public void embedEntities(String corpusId, Collection<Entity> entities) {
        if (corpusId == null || entities == null || !isSemantic(embeddingPort)) {
            return;
        }
        Map<String, float[]> embeddings = new LinkedHashMap<>();
        for (Entity entity : entities) {
            if (entity != null) {
                embeddings.put(entity.normalizedIdentity(), embeddingPort.embed(entityText(entity)));
            }
        }
        if (!embeddings.isEmpty()) {
            graphStorePort.persistEntityEmbeddings(corpusId, embeddings);
        }
    }

    static String entityText(Entity entity) {
        return entity.name() + ": " + entity.description();
    }

    private static <T> Collection<T> orEmpty(Collection<T> collection) {
        return collection == null ? java.util.List.of() : collection;
    }
}
