package com.infinitude.exception;

/** OTP does not match the persisted hash for the given email+purpose. */
public class InvalidOtpException extends OtpException {
    public InvalidOtpException() {
        super("OTP_INVALID", "The OTP is incorrect. Please check and try again.");
    }
}
