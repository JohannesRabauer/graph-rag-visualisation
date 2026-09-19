package com.graphraglens.core.port;

import com.graphraglens.core.domain.ExtractionResult;

/**
 * Port for large-language-model calls (extraction, summarization, querying).
 */
public interface LlmPort {

    /**
     * Extract entities and relationships from one source document text.
     *
     * @param text source text
     * @return extracted graph candidates
     */
    ExtractionResult extractEntitiesAndRelationships(String text);
}
