package com.graphraglens.core.domain;

import java.util.Map;

/**
 * Generic ingestion progress event for streaming progress updates.
 *
 * @param type event type identifier
 * @param data payload data
 */
public record IngestionProgressEvent(String type, Map<String, Object> data) {
}
