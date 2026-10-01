package dev.rabauer.graphrag.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

class CorpusProgressServiceTest {

    private final CorpusProgressService service = new CorpusProgressService();

    @AfterEach
    void shutDown() {
        service.shutdown();
    }

    @Test
    void progressStreamsNeverTimeOutSoALongLlmPassageCannotDisconnectThem() {
        SseEmitter emitter = service.register("corpus-1");

        // Zero means "no timeout" for the servlet async request; the old 30 s
        // limit ended the stream mid-ingestion whenever one passage took longer.
        assertThat(emitter.getTimeout()).isZero();
    }

    @Test
    void keepAliveKeepsOpenStreamsAndDropsOnesThatCanNoLongerBeWritten() {
        SseEmitter open = service.register("corpus-1");
        SseEmitter closed = service.register("corpus-1");
        closed.complete();

        service.sendKeepAlive();

        assertThat(service.openStreamCount("corpus-1")).isEqualTo(1);
        assertThat(open.getTimeout()).isZero();
    }

    @Test
    void keepAliveWithNoOpenStreamsIsANoOp() {
        service.sendKeepAlive();

        assertThat(service.openStreamCount("corpus-unknown")).isZero();
    }
}
