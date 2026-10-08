package com.verireview.check;

/**
 * Unified status for all check runs and steps.
 * QUEUED -> RUNNING -> SUCCEEDED/FAILED/SKIPPED
 * Steps can also be PENDING before they start.
 */
public enum CheckStatus {
  QUEUED,
  RUNNING,
  SUCCEEDED,
  FAILED,
  SKIPPED,
  PENDING
}