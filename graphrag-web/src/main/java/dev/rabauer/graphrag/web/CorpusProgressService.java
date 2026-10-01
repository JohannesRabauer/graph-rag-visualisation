package dev.rabauer.graphrag.web;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fans out per-Corpus SSE progress events. Because the async knowledge-graph
 * pipeline can (and, for the fast demo dataset, routinely does) complete
 * before the browser's {@code EventSource} subscribes, this service keeps a
 * small bounded replay buffer per corpusId: events emitted with no emitter
 * registered yet are not lost, they are replayed to the next emitter that
 * registers, in order, before it starts receiving live events.
 *
 * <p>Streams never time out: with a live LLM a single passage can take well
 * over a minute, and a fixed emitter timeout used to end the stream mid-
 * ingestion ("The progress stream disconnected"). Instead, every open stream
 * gets an SSE comment every {@value #KEEP_ALIVE_SECONDS} seconds, which keeps
 * idle connections (and proxies) open and drops emitters whose client is
 * gone. {@code EventSource} ignores comments, so the client needs no change.</p>
 */
@Component
public class CorpusProgressService {

    /**
     * Well above what a typical corpus produces. Raised from 500 in Story
     * 13.1: extraction now runs per Text Unit, and each unit re-reports the
     * Entities/Relationships it found (overlapping passages repeat some),
     * plus one {@code text-unit-extracted} event per unit.
     */
    private static final int MAX_BUFFERED_EVENTS_PER_CORPUS = 5_000;

    /** Zero means no timeout: the stream stays open until the client closes it. */
    static final long EMITTER_TIMEOUT_MS = 0L;

    static final long KEEP_ALIVE_SECONDS = 15L;

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emittersByCorpusId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<BufferedEvent>> bufferedEventsByCorpusId = new ConcurrentHashMap<>();

    private final ScheduledExecutorService keepAliveScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "corpus-progress-keep-alive");
        thread.setDaemon(true);
        return thread;
    });

    public CorpusProgressService() {
        keepAliveScheduler.scheduleAtFixedRate(this::sendKeepAlive,
                KEEP_ALIVE_SECONDS, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        keepAliveScheduler.shutdownNow();
    }

    public SseEmitter register(String corpusId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emittersByCorpusId.computeIfAbsent(corpusId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(corpusId, emitter));
        emitter.onTimeout(() -> removeEmitter(corpusId, emitter));
        emitter.onError(throwable -> removeEmitter(corpusId, emitter));

        replayBufferedEvents(corpusId, emitter);

        emit(corpusId, "heartbeat", Map.of("message", "Connection established."));
        return emitter;
    }

    public void emit(String corpusId, String eventType, Map<String, Object> payload) {
        if (corpusId == null || eventType == null) {
            return;
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", eventType);
        envelope.put("data", payload == null ? Map.of() : payload);

        bufferEvent(corpusId, eventType, envelope);

        List<SseEmitter> emitters = emittersByCorpusId.get(corpusId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        boolean delivered = false;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventType).data(envelope));
                delivered = true;
            } catch (IOException ex) {
                removeEmitter(corpusId, emitter);
            }
        }

        if (delivered && isTerminalEvent(eventType)) {
            bufferedEventsByCorpusId.remove(corpusId);
        }
    }

    /**
     * Sends an SSE comment to every open stream. It is not buffered and not
     * replayed, and an emitter that can no longer be written to is removed.
     */
    void sendKeepAlive() {
        emittersByCorpusId.forEach((corpusId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (IOException | IllegalStateException ex) {
                    removeEmitter(corpusId, emitter);
                }
            }
        });
    }

    int openStreamCount(String corpusId) {
        List<SseEmitter> emitters = emittersByCorpusId.get(corpusId);
        return emitters == null ? 0 : emitters.size();
    }

    private void replayBufferedEvents(String corpusId, SseEmitter emitter) {
        List<BufferedEvent> buffered = bufferedEventsByCorpusId.get(corpusId);
        if (buffered == null || buffered.isEmpty()) {
            return;
        }

        boolean sawTerminalEvent = false;
        for (BufferedEvent event : buffered) {
            try {
                emitter.send(SseEmitter.event().name(event.eventType()).data(event.envelope()));
                if (isTerminalEvent(event.eventType())) {
                    sawTerminalEvent = true;
                }
            } catch (IOException ex) {
                removeEmitter(corpusId, emitter);
                return;
            }
        }

        if (sawTerminalEvent) {
            bufferedEventsByCorpusId.remove(corpusId);
        }
    }

    private void bufferEvent(String corpusId, String eventType, Map<String, Object> envelope) {
        CopyOnWriteArrayList<BufferedEvent> buffer =
                bufferedEventsByCorpusId.computeIfAbsent(corpusId, ignored -> new CopyOnWriteArrayList<>());
        buffer.add(new BufferedEvent(eventType, envelope));
        while (buffer.size() > MAX_BUFFERED_EVENTS_PER_CORPUS) {
            buffer.remove(0);
        }
    }

    private static boolean isTerminalEvent(String eventType) {
        return "ingestion-complete".equals(eventType) || "error".equals(eventType);
    }

    private void removeEmitter(String corpusId, SseEmitter emitter) {
        if (corpusId == null || emitter == null) {
            return;
        }

        List<SseEmitter> emitters = emittersByCorpusId.get(corpusId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByCorpusId.remove(corpusId, emitters);
        }
    }

    private record BufferedEvent(String eventType, Map<String, Object> envelope) {
    }
}
