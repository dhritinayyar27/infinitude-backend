package com.infinitude.exception;

/** A resend was requested before {@code OTP_RESEND_COOLDOWN_SECONDS} elapsed. */
public class OtpCooldownException extends OtpException {
    public OtpCooldownException(long secondsRemaining) {
        super("OTP_RESEND_COOLDOWN", "Please wait " + secondsRemaining + " seconds before requesting another OTP.");
    }
}
