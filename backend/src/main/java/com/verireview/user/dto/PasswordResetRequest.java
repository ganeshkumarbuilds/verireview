package com.verireview.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Password-reset request: email only. The response never reveals whether
 *  the account exists (anti-enumeration). */
public record PasswordResetRequest(
    @NotBlank @Email @Size(max = 255) String email) {
}
