package com.verireview.user.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Public user profile. The password hash is never exposed (AGENTS.md rule 6).
 */
public record UserResponse(
    UUID id, String email, String displayName, Set<String> roles, boolean enabled,
    Instant createdAt) {
}
