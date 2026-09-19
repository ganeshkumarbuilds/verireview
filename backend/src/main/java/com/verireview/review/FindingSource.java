package com.verireview.review;

/**
 * Finding provenance. AI findings are never relabeled VERIFIED without the
 * backend verification gate (AGENTS.md rule 11).
 */
public enum FindingSource {
  DETERMINISTIC,
  AI,
  VERIFIED
}
