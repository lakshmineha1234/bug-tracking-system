package com.bugtracker.exception;

import org.springframework.http.HttpStatus;

/** Base class for errors whose message is safe to return to the client. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
