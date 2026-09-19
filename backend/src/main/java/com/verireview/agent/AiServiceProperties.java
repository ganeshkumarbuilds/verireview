package com.verireview.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AI-service connection settings (all environment-overridable, no secrets in
 * code). The secret travels only in the server-to-server Authorization
 * header — it is never exposed to the frontend.
 */
@Component
public class AiServiceProperties {

  private final String url;
  private final String secret;
  private final long timeoutSeconds;
  private final boolean enabled;
  private final String modelLabel;
  private final int maxFiles;
  private final long maxBytesPerFile;

  public AiServiceProperties(
      @Value("${app.ai-service.url:http://localhost:8001}") String url,
      @Value("${app.ai-service.secret:}") String secret,
      @Value("${app.ai-service.timeout-seconds:120}") long timeoutSeconds,
      @Value("${app.ai-service.enabled:true}") boolean enabled,
      @Value("${app.ai-service.model-label:ai-service/review}") String modelLabel,
      @Value("${app.ai-service.max-files:25}") int maxFiles,
      @Value("${app.ai-service.max-bytes-per-file:16384}") long maxBytesPerFile) {
    this.url = url;
    this.secret = secret;
    this.timeoutSeconds = timeoutSeconds;
    this.enabled = enabled;
    this.modelLabel = modelLabel;
    this.maxFiles = maxFiles;
    this.maxBytesPerFile = maxBytesPerFile;
  }

  public String url() {
    return url;
  }

  public String secret() {
    return secret;
  }

  public long timeoutSeconds() {
    return timeoutSeconds;
  }

  public boolean enabled() {
    return enabled;
  }

  public String modelLabel() {
    return modelLabel;
  }

  public int maxFiles() {
    return maxFiles;
  }

  public long maxBytesPerFile() {
    return maxBytesPerFile;
  }
}
