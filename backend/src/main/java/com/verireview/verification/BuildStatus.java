package com.verireview.verification;

public enum BuildStatus {
  PENDING,
  RUNNING,
  SUCCESS,
  FAILURE,
  TIMEOUT,
  /** Docker sandbox disabled: no build/test actually ran. */
  SKIPPED
}
