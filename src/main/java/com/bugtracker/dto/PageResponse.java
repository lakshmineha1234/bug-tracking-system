package com.bugtracker.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/** One page of results. page is zero-based. */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> p) {
        return new PageResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(), p.getTotalPages());
    }
}
