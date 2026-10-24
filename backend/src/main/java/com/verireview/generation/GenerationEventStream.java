package com.verireview.generation;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Pushes generation change notifications; clients fetch the authoritative DTO. */
@Service
public class GenerationEventStream {

  private static final Logger log = LoggerFactory.getLogger(GenerationEventStream.class);
  private static final long TIMEOUT_MS = 60 * 60 * 1000;

  private final ConcurrentHashMap<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

  public SseEmitter subscribe(UUID generationId) {
    SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
    Set<SseEmitter> subscribers =
        emitters.computeIfAbsent(generationId, ignored -> ConcurrentHashMap.newKeySet());
    subscribers.add(emitter);
    Runnable remove = () -> remove(generationId, emitter);
    emitter.onCompletion(remove);
    emitter.onTimeout(remove);
    emitter.onError(error -> remove.run());
    try {
      emitter.send(SseEmitter.event().name("ready").data(generationId.toString()));
    } catch (IOException e) {
      remove.run();
      log.debug("Could not open generation event stream: {}", e.toString());
      emitter.completeWithError(e);
    }
    return emitter;
  }

  public void publish(UUID generationId) {
    Set<SseEmitter> subscribers = emitters.get(generationId);
    if (subscribers == null) {
      return;
    }
    for (SseEmitter emitter : subscribers) {
      try {
        emitter.send(SseEmitter.event().name("update").data(generationId.toString()));
      } catch (IOException e) {
        remove(generationId, emitter);
        emitter.completeWithError(e);
        log.debug("Could not publish generation update: {}", e.toString());
      }
    }
  }

  private void remove(UUID generationId, SseEmitter emitter) {
    Set<SseEmitter> subscribers = emitters.get(generationId);
    if (subscribers != null) {
      subscribers.remove(emitter);
      if (subscribers.isEmpty()) {
        emitters.remove(generationId, subscribers);
      }
    }
  }
}
