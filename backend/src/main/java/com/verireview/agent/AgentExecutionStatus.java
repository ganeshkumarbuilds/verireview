package com.verireview.agent;

public enum AgentExecutionStatus {
  PENDING,
  RUNNING,
  COMPLETED,
  FAILED,
  /** The step could not run (for example the Docker sandbox is disabled). */
  SKIPPED
}
