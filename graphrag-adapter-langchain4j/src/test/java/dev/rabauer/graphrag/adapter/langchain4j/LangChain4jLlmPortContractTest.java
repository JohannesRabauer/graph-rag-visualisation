package dev.rabauer.graphrag.adapter.langchain4j;

import dev.rabauer.graphrag.core.port.LlmPort;
import dev.rabauer.graphrag.testkit.LlmPortContract;

/** The offline, deterministic LLM stub meets the testkit's LLM port contract. */
class LangChain4jLlmPortContractTest extends LlmPortContract {

    @Override
    protected LlmPort port() {
        return new LangChain4jLlmPort();
    }
}
