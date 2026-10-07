package com.verireview.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Jackson ObjectMapper configuration (Phase 1).
 *
 * <p>- ISO-8601 dates (no timestamps)
 * <p>- Fail on unknown properties for strict contract enforcement
 * <p>- No default typing (security)
 * <p>- Write dates as ISO strings, not arrays
 *
 * <p>Uses direct {@link ObjectMapper} configuration for Spring Boot 4.1.1 compatibility.
 * The deprecated {@code Jackson2ObjectMapperBuilder} bean is no longer auto-registered.
 */
@Configuration
public class JacksonConfig {

  @Bean
  @Primary
  ObjectMapper objectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    mapper.disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
    return mapper;
  }
}