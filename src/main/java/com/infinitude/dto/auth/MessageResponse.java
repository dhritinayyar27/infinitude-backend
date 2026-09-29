package com.infinitude.dto.auth;

/**
 * Generic message-only response, used for the send-otp endpoints (§17.5 PROJECT_ARCHITECTURE.md)
 * so response shape/status is identical regardless of whether the target email exists, and for
 * logout.
 */
public class MessageResponse {

    private String message;

    public MessageResponse() {
    }

    public MessageResponse(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
