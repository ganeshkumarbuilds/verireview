package com.verireview.review;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Pushes review changes; clients fetch the authoritative review response. */
@Service
public class ReviewEventStream {

  private static final Logger log = LoggerFactory.getLogger(ReviewEventStream.class);
  private static final long TIMEOUT_MS = 60 * 60 * 1000;
  private final ConcurrentHashMap<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

  public SseEmitter subscribe(UUID reviewId) {
    SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
    Set<SseEmitter> subscribers =
        emitters.computeIfAbsent(reviewId, ignored -> ConcurrentHashMap.newKeySet());
    subscribers.add(emitter);
    Runnable remove = () -> remove(reviewId, emitter);
    emitter.onCompletion(remove);
    emitter.onTimeout(remove);
    emitter.onError(error -> remove.run());
    try {
      emitter.send(SseEmitter.event().name("ready").data(reviewId.toString()));
    } catch (IOException e) {
      remove.run();
      emitter.completeWithError(e);
      log.debug("Could not open review event stream: {}", e.toString());
    }
    return emitter;
  }

  public void publish(UUID reviewId) {
    Set<SseEmitter> subscribers = emitters.get(reviewId);
    if (subscribers == null) return;
    for (SseEmitter emitter : subscribers) {
      try {
        emitter.send(SseEmitter.event().name("update").data(reviewId.toString()));
      } catch (IOException e) {
        remove(reviewId, emitter);
        emitter.completeWithError(e);
      }
    }
  }

  private void remove(UUID reviewId, SseEmitter emitter) {
    Set<SseEmitter> subscribers = emitters.get(reviewId);
    if (subscribers != null) {
      subscribers.remove(emitter);
      if (subscribers.isEmpty()) emitters.remove(reviewId, subscribers);
    }
  }
}
