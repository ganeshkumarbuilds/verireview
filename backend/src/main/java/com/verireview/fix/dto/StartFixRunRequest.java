package com.verireview.fix.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

/**
 * Request to start a fix run for a set of findings.
 *
 * <p>The backend refuses to start a fix run unless an approved {@code FixRequest}
 * already exists for every finding (the user's explicit consent step).
 */
public record StartFixRunRequest(
    @NotEmpty(message = "At least one finding must be selected") List<UUID> findingIds,
    String scopeNote) {}
