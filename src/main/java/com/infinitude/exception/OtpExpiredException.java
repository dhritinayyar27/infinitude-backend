package com.infinitude.exception;

/** OTP existed but its expiry window has passed. */
public class OtpExpiredException extends OtpException {
    public OtpExpiredException() {
        super("OTP_EXPIRED", "This OTP has expired. Please request a new one.");
    }
}
