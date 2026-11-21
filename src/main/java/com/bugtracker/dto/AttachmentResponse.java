package com.bugtracker.dto;

import com.bugtracker.entity.Attachment;

import java.time.Instant;

/** canDelete tells the frontend whether to show a delete button; the backend still checks. */
public record AttachmentResponse(Long id, Long bugId, String filename, String contentType, long sizeBytes,
                                 boolean image, UserSummary uploadedBy, Instant createdAt, boolean canDelete) {

    public static AttachmentResponse from(Attachment a, boolean canDelete) {
        return new AttachmentResponse(a.getId(), a.getBug().getId(), a.getOriginalFilename(), a.getContentType(),
                a.getSizeBytes(), a.isImage(), UserSummary.from(a.getUploadedBy()), a.getCreatedAt(), canDelete);
    }
}
