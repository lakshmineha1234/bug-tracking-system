package com.bugtracker.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** An admin sets a new password for another user. */
public record ResetPasswordRequest(@NotBlank @Size(min = 8, max = 72) String newPassword) {
}
