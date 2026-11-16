package com.bugtracker.dto;

import com.bugtracker.entity.Project;

import java.time.Instant;

public record ProjectResponse(Long id, String projectName, String description, UserSummary createdBy,
                              Instant createdAt, int memberCount) {

    public static ProjectResponse from(Project p) {
        return new ProjectResponse(p.getId(), p.getProjectName(), p.getDescription(),
                UserSummary.from(p.getCreatedBy()), p.getCreatedAt(), p.getMembers().size());
    }
}
