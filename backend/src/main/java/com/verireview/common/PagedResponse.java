package com.verireview.common;

import java.util.List;
import org.springframework.data.domain.Page;

/** Stable paged shape (API_DESIGN §1: 0-based page, content + totals). */
public record PagedResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages) {

  public static <T> PagedResponse<T> of(Page<T> page) {
    return new PagedResponse<>(
        page.getContent(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages());
  }
}
