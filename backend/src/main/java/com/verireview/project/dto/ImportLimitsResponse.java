package com.verireview.project.dto;

/**
 * Response DTO for the import limits endpoint.
 */
public record ImportLimitsResponse(
    long maxZipBytes,
    int maxFiles,
    long maxTotalUncompressedBytes,
    long maxSingleFileBytes,
    String maxZipBytesHuman,
    String maxTotalUncompressedBytesHuman,
    String maxSingleFileBytesHuman
) {
}