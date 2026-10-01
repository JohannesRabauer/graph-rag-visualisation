package com.graphraglens.web;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fans out per-Corpus SSE progress events. Because the async knowledge-graph
 * pipeline can (and, for the fast demo dataset, routinely does) complete
 * before the browser's {@code EventSource} subscribes, this service keeps a
 * small bounded replay buffer per corpusId: events emitted with no emitter
 * registered yet are not lost, they are replayed to the next emitter that
 * registers, in order, before it starts receiving live events.
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

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emittersByCorpusId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<BufferedEvent>> bufferedEventsByCorpusId = new ConcurrentHashMap<>();

    public SseEmitter register(String corpusId) {
        SseEmitter emitter = new SseEmitter(30_000L);
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
