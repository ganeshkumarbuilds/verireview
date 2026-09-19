package com.verireview.agent;

import com.verireview.fix.FixRequest;
import com.verireview.review.Finding;
import org.springframework.stereotype.Component;

/**
 * Deterministic placeholder (Phase 9C). Generates a minimal, syntactically valid unified diff
 * without touching the filesystem or invoking any model. The diff is intentionally trivial
 * and must never be presented as verified or applied.
 */
@Component
public class DeterministicCodingAgent implements CodingAgent {

  @Override
  public Proposal propose(FixRequest fixRequest) {
    Finding finding = fixRequest.getFinding();
    String filePath = finding.getFilePath() != null ? finding.getFilePath() : "src/Placeholder.java";
    // Normalize to relative path without leading slash for diff header
    String normalized = filePath.startsWith("/") ? filePath.substring(1) : filePath;
    String safeTitle = finding.getTitle() != null ? finding.getTitle().replace("\n", " ").trim() : "fix";

    String diff = """
        diff --git a/%1$s b/%1$s
        --- a/%1$s
        +++ b/%1$s
        @@ -1,3 +1,4 @@
         // placeholder for finding: %2$s
        +// FIX (proposed, not applied): %2$s
         // finding: %3$s
        """.formatted(
            normalized,
            safeTitle,
            safeTitle);

    // Metadata is deterministic and minimal; caller validates before saving.
    return new Proposal(diff, 1, 1, 0);
  }
}
