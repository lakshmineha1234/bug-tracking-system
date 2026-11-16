package com.bugtracker.dto;

import com.bugtracker.entity.Comment;

import java.time.Instant;

public record CommentResponse(Long id, UserSummary author, String commentText, Instant createdAt) {

    public static CommentResponse from(Comment c) {
        return new CommentResponse(c.getId(), UserSummary.from(c.getUser()), c.getCommentText(), c.getCreatedAt());
    }
}
