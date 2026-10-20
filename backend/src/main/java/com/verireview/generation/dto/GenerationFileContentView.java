package com.verireview.generation.dto;

/**
 * One generated text file for the browse view. {@code truncated} is true when
 * the file exceeded the safe preview cap; {@code binary} files are never
 * inlined (content is null).
 */
public record GenerationFileContentView(
    String path,
    String content,
    boolean truncated,
    boolean binary) {
}
