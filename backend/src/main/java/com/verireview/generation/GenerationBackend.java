package com.verireview.generation;

/** Explicit backend choices for a generated project. No hidden defaults. */
public enum GenerationBackend {
  JAVA_SPRING_BOOT,
  NODEJS,
  PYTHON_FASTAPI,
  /** User typed a backend the catalog does not know; see {@code backendCustom}. */
  OTHER,
  NONE
}
