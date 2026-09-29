package com.infinitude.exception;

/** Thrown by the in-memory rate limiter guarding the send-otp endpoints (§17.4/§11). */
public class RateLimitExceededException extends OtpException {
    public RateLimitExceededException() {
        super("RATE_LIMIT_EXCEEDED", "Too many requests. Please try again later.");
    }
}
