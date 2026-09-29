package com.infinitude.exception;

/** The OTP record has exceeded {@code OTP_MAX_ATTEMPTS} verification attempts and is locked. */
public class OtpLockedException extends OtpException {
    public OtpLockedException() {
        super("OTP_LOCKED", "Too many incorrect attempts. Please request a new OTP.");
    }
}
