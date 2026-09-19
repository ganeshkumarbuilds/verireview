package com.verireview.agent;

import com.verireview.fix.FixRequest;

/**
 * Coding Agent contract (Phase 9C foundation). Returns a deterministic placeholder proposal.
 * Must NOT modify files, execute code, or claim verification. Implemented as skeleton now;
 * LLM-backed reasoning arrives later.
 */
public interface CodingAgent {

  /**
   * Produces a proposed unified diff for the given FixRequest.
   * Must be pure: no file writes, no process execution, no DB mutation.
   */
  Proposal propose(FixRequest fixRequest);

  /**
   * Simple value for diff and metadata. Validated by caller before persistence.
   */
  record Proposal(
      String diff,
      int filesChanged,
      int additions,
      int deletions) {
  }
}
