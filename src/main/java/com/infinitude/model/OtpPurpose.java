package com.infinitude.model;

/**
 * Distinguishes login OTPs from signup OTPs (§5.1.1, §17.1 PROJECT_ARCHITECTURE.md) so an
 * OTP issued for one purpose can never be replayed to satisfy the other.
 */
public enum OtpPurpose {
    LOGIN,
    SIGNUP
}
