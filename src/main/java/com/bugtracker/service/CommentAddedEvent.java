package com.bugtracker.service;

/** Published when someone comments on a bug. assigneeId is the developer assigned at that moment (null if none). */
public record CommentAddedEvent(Long commentId, Long assigneeId) {
}
