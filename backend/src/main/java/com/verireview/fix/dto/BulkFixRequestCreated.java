package com.verireview.fix.dto;

import java.util.List;
import java.util.UUID;

/** Result of a bulk consent step: which fix requests were created. */
public record BulkFixRequestCreated(
    UUID projectId,
    List<UUID> fixRequestIds,
    int created,
    int skipped) {}
