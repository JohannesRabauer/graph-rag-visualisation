package dev.rabauer.graphrag.testkit;

import dev.rabauer.graphrag.core.port.CachingGraphReadPort;
import dev.rabauer.graphrag.core.port.GraphReadPort;

/** The caching read view meets the read contract over the reference store. */
class CachingGraphReadPortContractTest extends GraphReadPortContract {

    @Override
    protected GraphReadPort givenGraph(ContractGraph graph) {
        InMemoryGraphStore store = new InMemoryGraphStore();
        persist(store, graph);
        return new CachingGraphReadPort(store);
    }
}
