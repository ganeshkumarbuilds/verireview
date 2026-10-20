package com.verireview.execution;

public enum ExecutionStatus {
  PENDING,
  RUNNING,
  SUCCESS,
  FAILURE,
  TIMEOUT,
  /** Docker sandbox disabled: the build/test was never actually run. */
  SKIPPED
}
