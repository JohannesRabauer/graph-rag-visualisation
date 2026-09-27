package io.graphrag.core.domain;

public record Chunk(String id, String corpusId, int ordinal, String text) {
}
