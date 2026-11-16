package com.bugtracker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectRequest(
        @NotBlank @Size(max = 100) String projectName,
        @Size(max = 1000) String description) {
}
