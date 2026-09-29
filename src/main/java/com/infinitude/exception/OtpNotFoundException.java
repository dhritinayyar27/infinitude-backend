package com.infinitude.exception;

/** No active/unused, unexpired OTP record exists for this email+purpose. */
public class OtpNotFoundException extends OtpException {
    public OtpNotFoundException() {
        super("OTP_NOT_FOUND", "No active OTP found for this request. Please request a new one.");
    }
}
