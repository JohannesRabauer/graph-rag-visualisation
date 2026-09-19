package com.graphraglens.web;

import com.graphraglens.core.domain.IngestionProgressEvent;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Multiplexed SSE progress stream registry keyed by corpus id.
 */
@Component
public class IngestionProgressBroker {

    private final Map<String, List<SseEmitter>> emittersByCorpusId = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String corpusId) {
        SseEmitter emitter = new SseEmitter(0L);
        emittersByCorpusId.computeIfAbsent(corpusId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(corpusId, emitter));
        emitter.onTimeout(() -> removeEmitter(corpusId, emitter));
        emitter.onError(ignored -> removeEmitter(corpusId, emitter));
        return emitter;
    }

    public void publish(String corpusId, IngestionProgressEvent event) {
        List<SseEmitter> emitters = emittersByCorpusId.get(corpusId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        Map<String, Object> envelope = Map.of("type", event.type(), "data", event.data());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.type())
                        .data(envelope, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                removeEmitter(corpusId, emitter);
            }
        }
    }

    private void removeEmitter(String corpusId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByCorpusId.get(corpusId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByCorpusId.remove(corpusId);
        }
    }
}
