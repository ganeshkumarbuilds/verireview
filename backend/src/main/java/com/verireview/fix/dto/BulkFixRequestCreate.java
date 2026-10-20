package com.verireview.fix.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

/** Bulk consent: create approved fix requests for the selected findings. */
public record BulkFixRequestCreate(
    @NotEmpty(message = "At least one finding must be selected") List<UUID> findingIds,
    String scopeNote) {}
