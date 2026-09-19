package com.graphraglens.core.domain;

/**
 * A single uploaded document's filename and decoded text content.
 * Framework-free input to {@code IngestCorpus} — no {@code MultipartFile} or
 * other web concept leaks into {@code graphrag-core} (AD-1).
 *
 * @param filename the original filename as supplied by the uploader
 * @param content the document's decoded text content
 */
public record UploadedDocument(String filename, String content) {
}
