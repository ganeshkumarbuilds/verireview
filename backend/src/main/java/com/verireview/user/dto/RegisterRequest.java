package com.verireview.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration input (API_DESIGN §2: email, password ≥ 8 chars,
 * displayName). Bean Validation runs before any service logic.
 */
public record RegisterRequest(
    @NotBlank @Email @Size(max = 255) String email,
    @NotBlank @Size(min = 8, max = 200) String password,
    @Size(max = 100) String displayName) {
}
