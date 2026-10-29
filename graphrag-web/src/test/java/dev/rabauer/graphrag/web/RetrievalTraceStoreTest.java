package dev.rabauer.graphrag.web;

import dev.rabauer.graphrag.core.domain.RetrievalTrace;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetrievalTraceStoreTest {

    private static RetrievalTrace trace(String id) {
        return new RetrievalTrace(id, List.of());
    }

    @Test
    void neverHoldsMoreThanTheConfiguredMaximum() {
        RetrievalTraceStore store = new RetrievalTraceStore(3);

        for (int i = 0; i < 10; i++) {
            store.put("t" + i, trace("t" + i));
            assertThat(store.size()).isLessThanOrEqualTo(3);
        }

        assertThat(store.size()).isEqualTo(3);
    }

    @Test
    void evictsOldestFirstAndEvictedTraceIsUnknown() {
        RetrievalTraceStore store = new RetrievalTraceStore(2);
        store.put("a", trace("a"));
        store.put("b", trace("b"));

        store.put("c", trace("c"));

        assertThat(store.get("a")).isEmpty();
        assertThat(store.get("b")).isPresent();
        assertThat(store.get("c")).isPresent();
    }

    @Test
    void readingATraceKeepsItAndEvictsTheLeastRecentlyUsedInstead() {
        RetrievalTraceStore store = new RetrievalTraceStore(2);
        store.put("a", trace("a"));
        store.put("b", trace("b"));
        assertThat(store.get("a")).isPresent();

        store.put("c", trace("c"));

        assertThat(store.get("a")).isPresent();
        assertThat(store.get("b")).isEmpty();
        assertThat(store.get("c")).isPresent();
    }

    @Test
    void reputtingAnIdDoesNotGrowTheStore() {
        RetrievalTraceStore store = new RetrievalTraceStore(2);
        store.put("a", trace("a"));
        store.put("a", trace("a"));

        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void defaultsTo500() {
        assertThat(new RetrievalTraceStore().maxTraces()).isEqualTo(500);
    }

    @Test
    void rejectsANonPositiveMaximum() {
        assertThatThrownBy(() -> new RetrievalTraceStore(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsTheMaximumFromTheGraphragTracesMaxSetting() {
        new ApplicationContextRunner()
                .withBean(RetrievalTraceStore.class)
                .withPropertyValues("graphrag.traces.max=7")
                .run(ctx -> assertThat(ctx.getBean(RetrievalTraceStore.class).maxTraces()).isEqualTo(7));
    }

    @Test
    void usesTheDefaultWhenTheSettingIsAbsent() {
        new ApplicationContextRunner()
                .withBean(RetrievalTraceStore.class)
                .run(ctx -> assertThat(ctx.getBean(RetrievalTraceStore.class).maxTraces()).isEqualTo(500));
    }
}
