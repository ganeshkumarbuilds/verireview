package com.verireview.fix.dto;

/**
 * One file changed by a fix run.
 *
 * @param path    project-relative file path
 * @param patchId the applied patch (used to revert this file to its previous content)
 * @param status  APPLIED or REVERTED
 * @param diff    the unified diff that was applied
 */
public record FixRunChangedFileResponse(
    String path,
    String patchId,
    String status,
    String diff) {}
