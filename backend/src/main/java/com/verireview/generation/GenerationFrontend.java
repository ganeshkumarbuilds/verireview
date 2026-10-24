package com.verireview.generation;

/** Explicit frontend choices for a generated project. No hidden defaults. */
public enum GenerationFrontend {
  REACT_TYPESCRIPT,
  NEXTJS,
  VUE,
  /** User typed a frontend the catalog does not know; see {@code frontendCustom}. */
  OTHER,
  NONE
}
