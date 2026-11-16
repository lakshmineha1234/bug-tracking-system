package com.bugtracker.dto;

import com.bugtracker.entity.Priority;
import com.bugtracker.entity.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateBugRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 4000) String description,
        @Size(max = 4000) String stepsToReproduce,
        @Size(max = 2000) String expectedResult,
        @Size(max = 2000) String actualResult,
        @NotNull Priority priority,
        @NotNull Severity severity,
        @NotNull Long projectId) {
}
