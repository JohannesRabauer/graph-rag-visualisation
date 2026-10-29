package dev.rabauer.graphrag.testkit;

import dev.rabauer.graphrag.core.port.GraphStorePort;

/** The reference store meets the full store contract. */
class InMemoryGraphStoreContractTest extends GraphStorePortContract {

    @Override
    protected GraphStorePort newStore() {
        return new InMemoryGraphStore();
    }

    @Override
    protected boolean supportsDeletion() {
        return true;
    }

    @Override
    protected boolean supportsSimilarity() {
        return true;
    }
}
