package com.verireview.verification;

/**
 * Backend-enforced verdict. VERIFIED is persisted only when the verification
 * policy (build + tests + no new CRITICAL/HIGH) passes — enforced in a later
 * phase, preserved here as a constrained value set plus a mandatory patch link.
 */
public enum VerificationVerdict {
  PENDING,
  VERIFIED,
  REJECTED
}
