package com.bugtracker.service;

/** Published when someone comments on a bug. */
public record CommentAddedEvent(Long commentId) {
}
