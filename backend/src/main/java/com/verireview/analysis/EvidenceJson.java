package com.verireview.analysis;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/** Tiny JSON builder for the {@code findings.evidence} JSONB column. */
final class EvidenceJson {

  private static final Logger log = LoggerFactory.getLogger(EvidenceJson.class);
  private static final ObjectMapper OBJECTS = new ObjectMapper();

  private EvidenceJson() {
  }

  static String of(Map<String, String> fields) {
    try {
      return OBJECTS.writeValueAsString(new LinkedHashMap<>(fields));
    } catch (Exception e) {
      log.warn("Could not serialize finding evidence; storing empty object");
      return "{}";
    }
  }
}
