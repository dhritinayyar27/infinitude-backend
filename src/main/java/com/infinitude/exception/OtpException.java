package com.infinitude.exception;

/**
 * Base type for OTP-related failures (§17.4 PROJECT_ARCHITECTURE.md). Messages on subclasses
 * are intentionally generic/safe - they never leak internal detail (e.g. whether an email
 * exists, or exact hash-comparison internals).
 */
public class OtpException extends RuntimeException {

    private final String errorCode;

    public OtpException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
