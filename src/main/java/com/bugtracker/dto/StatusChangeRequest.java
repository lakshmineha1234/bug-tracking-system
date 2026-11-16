package com.bugtracker.dto;

import com.bugtracker.entity.BugStatus;
import jakarta.validation.constraints.NotNull;

public record StatusChangeRequest(@NotNull BugStatus status) {
}
