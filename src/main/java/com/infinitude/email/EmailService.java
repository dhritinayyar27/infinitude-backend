package com.infinitude.email;

import com.infinitude.model.OtpPurpose;

/**
 * Abstraction over OTP email delivery (§17.2 PROJECT_ARCHITECTURE.md). Keeping this as an
 * interface (mirroring the {@code AiService} pattern used for Gemini) means the concrete
 * transport (SMTP today, a provider API later) can change without touching callers.
 */
public interface EmailService {

    /**
     * Sends a one-time passcode to {@code toEmail}. The raw OTP is only ever held in memory
     * for the duration of this call - it is never persisted or logged in plaintext (§17.4).
     *
     * @param toEmail recipient address
     * @param otp     plaintext 6-digit OTP (transient - not stored)
     * @param purpose whether this OTP is for LOGIN or SIGNUP, used to tailor the email copy
     */
    void sendOtpEmail(String toEmail, String otp, OtpPurpose purpose);
}
