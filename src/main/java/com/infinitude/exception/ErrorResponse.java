package com.infinitude.exception;

import java.time.LocalDateTime;

/**
 * Consistent error response shape (§10 PROJECT_ARCHITECTURE.md):
 * <pre>
 * {
 *   "timestamp": "2026-09-25T12:30:00",
 *   "status": 500,
 *   "error": "AI_GENERATION_FAILED",
 *   "message": "Unable to generate section notes.",
 *   "path": "/api/notes/101/generate"
 * }
 * </pre>
 */
public class ErrorResponse {

    private final LocalDateTime timestamp;
    private final int status;
    private final String error;
    private final String message;
    private final String path;

    public ErrorResponse(int status, String error, String message, String path) {
        this.timestamp = LocalDateTime.now();
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public String getPath() {
        return path;
    }
}
