package com.verireview.generation;

import com.verireview.generation.dto.GenerationPlanRequest;
import com.verireview.generation.dto.GenerationQuestion;
import com.verireview.generation.dto.GenerationQuestionsRequest;
import com.verireview.generation.dto.PlanPreviewResponse;
import com.verireview.security.VeriReviewUserDetails;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Generate wizard contract: clarifying questions (step 3) and the plan
 * preview gate (step 4). Both are deterministic backend calls; no job is
 * created and no LLM is invoked until the user explicitly approves.
 */
@RestController
@RequestMapping("/api/v1/generate")
public class GenerateWizardController {

  private final GenerationWizardService wizard;

  public GenerateWizardController(GenerationWizardService wizard) {
    this.wizard = wizard;
  }

  @PostMapping("/questions")
  public ResponseEntity<List<GenerationQuestion>> questions(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @Valid @RequestBody GenerationQuestionsRequest request) {
    return ResponseEntity.ok(wizard.questions(request));
  }

  @PostMapping("/plan")
  public ResponseEntity<PlanPreviewResponse> plan(
      @AuthenticationPrincipal VeriReviewUserDetails principal,
      @Valid @RequestBody GenerationPlanRequest request) {
    return ResponseEntity.ok(wizard.plan(request));
  }
}
