package dev.rabauer.graphrag.testkit;

import dev.rabauer.graphrag.core.port.LlmPort;

/** {@link LlmPort#none()} meets the LLM port contract. */
class NoLlmPortContractTest extends LlmPortContract {

    @Override
    protected LlmPort port() {
        return LlmPort.none();
    }
}
