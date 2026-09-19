package com.graphraglens.core.domain;

/**
 * Raw uploaded file input (filename + bytes) passed from the web layer into
 * core use cases without leaking framework types.
 *
 * @param filename original upload filename
 * @param bytes uploaded bytes
 */
public record UploadedFile(String filename, byte[] bytes) {
}
