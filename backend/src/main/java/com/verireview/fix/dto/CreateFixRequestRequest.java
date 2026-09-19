package com.verireview.fix.dto;

import jakarta.validation.constraints.Size;

/** Explicit approval to fix one finding. Scope note is optional context. */
public record CreateFixRequestRequest(
    @Size(max = 5000) String scopeNote) {
}
