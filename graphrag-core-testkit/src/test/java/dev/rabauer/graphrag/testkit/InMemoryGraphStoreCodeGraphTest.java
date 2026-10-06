package dev.rabauer.graphrag.testkit;

import dev.rabauer.graphrag.core.port.GraphStorePort;

/** The code-graph scenario on the reference store. */
class InMemoryGraphStoreCodeGraphTest extends CodeGraphRetrievalContract {

    @Override
    protected GraphStorePort newStore() {
        return new InMemoryGraphStore();
    }
}
