package dev.rabauer.graphrag.core.domain;

/**
 * One row of the vector baseline's similarity ranking: a chunk with its
 * cosine similarity to the question and its position among all scored chunks.
 *
 * @param rank         the 1-based position in descending score order
 * @param chunkId      the chunk's id; never null
 * @param documentName the document the chunk was cut from ({@code ""} when unknown)
 * @param excerpt      the first 200 characters of the chunk text,
 *                     whitespace-collapsed, with "…" when cut (the citation rule)
 * @param score        the cosine similarity to the question embedding
 * @param used         whether the chunk is one of the top-k chunks that feed the answer
 */
public record RankedChunk(int rank, String chunkId, String documentName, String excerpt, double score,
                          boolean used) {

    public RankedChunk {
        chunkId = chunkId == null ? "" : chunkId;
        documentName = documentName == null ? "" : documentName;
        excerpt = excerpt == null ? "" : excerpt;
    }
}
