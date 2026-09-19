package com.graphraglens.web;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class CorpusProgressService {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emittersByCorpusId = new ConcurrentHashMap<>();

    public SseEmitter register(String corpusId) {
        SseEmitter emitter = new SseEmitter(30_000L);
        emittersByCorpusId.computeIfAbsent(corpusId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(corpusId, emitter));
        emitter.onTimeout(() -> removeEmitter(corpusId, emitter));
        emitter.onError(throwable -> removeEmitter(corpusId, emitter));

        emit(corpusId, "heartbeat", Map.of("message", "Connection established."));
        return emitter;
    }

    public void emit(String corpusId, String eventType, Map<String, Object> payload) {
        if (corpusId == null || eventType == null) {
            return;
        }

        List<SseEmitter> emitters = emittersByCorpusId.get(corpusId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", eventType);
        envelope.put("data", payload == null ? Map.of() : payload);

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventType).data(envelope));
            } catch (IOException ex) {
                removeEmitter(corpusId, emitter);
            }
        }
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
}
