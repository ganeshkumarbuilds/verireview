package com.verireview.pipeline;

import com.verireview.pipeline.dto.PipelineResponse;
import com.verireview.security.VeriReviewUserDetails;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PipelineController {

  private final PipelineService pipeline;

  public PipelineController(PipelineService pipeline) {
    this.pipeline = pipeline;
  }

  /**
   * The 5-stage agent pipeline for a project (Import -> Analyze -> Review -> Fix -> Verify),
   * derived from real records. Never 500s for a project with no data.
   */
  @GetMapping("/projects/{id}/pipeline")
  public ResponseEntity<PipelineResponse> getPipeline(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @PathVariable("id") UUID projectId) {
    return ResponseEntity.ok(pipeline.getPipeline(principal.getId(), projectId));
  }
}
