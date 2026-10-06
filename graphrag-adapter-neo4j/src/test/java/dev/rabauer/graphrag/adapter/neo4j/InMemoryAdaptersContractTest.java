package dev.rabauer.graphrag.adapter.neo4j;

import dev.rabauer.graphrag.core.port.GraphStorePort;
import dev.rabauer.graphrag.core.port.VectorStorePort;
import dev.rabauer.graphrag.testkit.CodeGraphRetrievalContract;
import dev.rabauer.graphrag.testkit.GraphStorePortContract;
import dev.rabauer.graphrag.testkit.VectorStorePortContract;
import org.junit.jupiter.api.Nested;

/** The in-memory adapters of this module meet the graphrag-core-testkit contracts. */
class InMemoryAdaptersContractTest {

    @Nested
    class GraphStore extends GraphStorePortContract {
        @Override
        protected GraphStorePort newStore() {
            return new InMemoryGraphStoreAdapter();
        }

        /** The in-memory adapter keeps no embeddings and always answers similarity lookups empty. */
        @Override
        protected boolean supportsSimilarity() {
            return false;
        }
    }

    @Nested
    class VectorStore extends VectorStorePortContract {
        @Override
        protected VectorStorePort newStore() {
            return new InMemoryVectorStoreAdapter();
        }
    }

    /** The code-graph scenario (import, core community detection, retrieval-only Local and Global) on the adapter. */
    @Nested
    class CodeGraph extends CodeGraphRetrievalContract {
        @Override
        protected GraphStorePort newStore() {
            return new InMemoryGraphStoreAdapter();
        }
    }
}
