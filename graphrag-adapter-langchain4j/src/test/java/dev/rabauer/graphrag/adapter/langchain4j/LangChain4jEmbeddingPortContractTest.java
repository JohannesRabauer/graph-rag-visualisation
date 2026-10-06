package dev.rabauer.graphrag.adapter.langchain4j;

import dev.rabauer.graphrag.core.port.EmbeddingPort;
import dev.rabauer.graphrag.testkit.EmbeddingPortContract;

/** The offline, deterministic embedding stub meets the testkit's embedding contract. */
class LangChain4jEmbeddingPortContractTest extends EmbeddingPortContract {

    @Override
    protected EmbeddingPort port() {
        return new LangChain4jEmbeddingPort();
    }
}
