package dev.rabauer.graphrag.web;

import dev.rabauer.graphrag.adapter.neo4j.Neo4jCorpusRegistry;
import dev.rabauer.graphrag.core.port.DocumentParserPort;
import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.core.port.VectorStorePort;
import dev.rabauer.graphrag.core.usecase.AnswerVectorBaseline;
import dev.rabauer.graphrag.core.usecase.ConstructVectorIndex;
import dev.rabauer.graphrag.core.usecase.IngestCorpus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Test helper: builds the corpus controllers (ingest, graph, query, compare,
 * trace) from the dependency list the former single {@code CorpusController}
 * took, so the endpoint tests keep wiring them the way they always did. It
 * only delegates; every call lands on the real controller.
 */
final class CorpusControllers {

    final CorpusIngestController ingest;
    final CorpusGraphController graph;
    final CorpusQueryController query;
    final CorpusCompareController compare;
    final TraceController trace;

    CorpusControllers(IngestCorpus ingestCorpus, Neo4jCorpusRegistry corpusStore,
                      List<DocumentParserPort> documentParsers, DemoDatasetService demoDatasetService,
                      CorpusProgressService corpusProgressService, LlmPort llmPort, GraphStorePort graphStorePort,
                      RetrievalTraceStore retrievalTraceStore, ConstructVectorIndex constructVectorIndex,
                      AnswerVectorBaseline answerVectorBaseline, VectorStorePort vectorStorePort) {
        this(ingestCorpus, corpusStore, documentParsers, demoDatasetService, corpusProgressService, llmPort,
                graphStorePort, retrievalTraceStore, constructVectorIndex, answerVectorBaseline, vectorStorePort,
                null);
    }

    CorpusControllers(IngestCorpus ingestCorpus, Neo4jCorpusRegistry corpusStore,
                      List<DocumentParserPort> documentParsers, DemoDatasetService demoDatasetService,
                      CorpusProgressService corpusProgressService, LlmPort llmPort, GraphStorePort graphStorePort,
                      RetrievalTraceStore retrievalTraceStore, ConstructVectorIndex constructVectorIndex,
                      AnswerVectorBaseline answerVectorBaseline, VectorStorePort vectorStorePort,
                      EmbeddingPort embeddingPort) {
        this.ingest = new CorpusIngestController(ingestCorpus, corpusStore, documentParsers, demoDatasetService,
                corpusProgressService, llmPort, graphStorePort, constructVectorIndex, vectorStorePort, embeddingPort);
        this.graph = new CorpusGraphController(corpusStore, graphStorePort, vectorStorePort, llmPort,
                embeddingPort);
        this.query = new CorpusQueryController(corpusStore, llmPort, graphStorePort, retrievalTraceStore,
                answerVectorBaseline, embeddingPort);
        this.compare = new CorpusCompareController(corpusStore, llmPort, graphStorePort, retrievalTraceStore,
                answerVectorBaseline, vectorStorePort, embeddingPort);
        this.trace = new TraceController(retrievalTraceStore);
    }

    /** A standalone MockMvc with every corpus controller registered. */
    MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(ingest, graph, query, compare, trace).build();
    }

    ResponseEntity<Map<String, Object>> upload(List<MultipartFile> files) {
        return ingest.upload(files);
    }

    ResponseEntity<Map<String, Object>> useOfflineDemoDataset() {
        return ingest.useOfflineDemoDataset();
    }

    ResponseEntity<Map<String, Object>> query(String corpusId, Map<String, String> request) {
        return query.query(corpusId, request);
    }

    ResponseEntity<Map<String, Object>> vectorSpace(String corpusId) {
        return graph.vectorSpace(corpusId);
    }

    ResponseEntity<Map<String, Object>> corpora() {
        return graph.corpora();
    }

    ResponseEntity<Map<String, Object>> trace(String traceId) {
        return trace.trace(traceId);
    }
}
