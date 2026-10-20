package com.verireview.pipeline.dto;

import java.util.List;
import java.util.UUID;

/** The ordered agent pipeline for one project. */
public record PipelineResponse(
    UUID projectId,
    List<PipelineStageResponse> stages) {}
