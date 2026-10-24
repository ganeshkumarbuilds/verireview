package com.verireview.generation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One answer to a {@link GenerationQuestion}. {@code value} is the raw choice
 * or text; {@code skipped} marks an explicit "ask me later" answer and is only
 * accepted when the question allows it.
 */
public record GenerationAnswerRequest(
    @NotBlank @Size(max = 200) String questionId,
    @Size(max = 5000) String value,
    boolean skipped) {
}
