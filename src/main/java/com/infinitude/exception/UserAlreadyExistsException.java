package com.infinitude.exception;

/**
 * Thrown when signup verification succeeds against the OTP record but the email has since
 * become a registered user (e.g. a race between two signup attempts, or a signup OTP replayed
 * after the account was already created by another verify call). Kept generic so it doesn't
 * leak details beyond "this didn't work, try again".
 */
public class UserAlreadyExistsException extends OtpException {
    public UserAlreadyExistsException() {
        super("USER_ALREADY_EXISTS", "An account for this email already exists. Please log in instead.");
    }
}
