package com.verireview.fix;

public enum PatchStatus {
  PROPOSED,
  APPLIED,
  REJECTED,
  /** The applied patch was rolled back and the previous file content restored. */
  REVERTED
}
