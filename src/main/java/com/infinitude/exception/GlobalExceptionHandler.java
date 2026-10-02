package com.infinitude.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.stream.Collectors;

/**
 * Centralized error handling (§10 PROJECT_ARCHITECTURE.md). Every response uses the same
 * {@link ErrorResponse} shape. Internal exception details/stack traces are never included in
 * the response body - only logged server-side.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                            HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Validation failed.";
        }
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, request);
    }

    @ExceptionHandler(OtpException.class)
    public ResponseEntity<ErrorResponse> handleOtpException(OtpException ex, HttpServletRequest request) {
        HttpStatus status = switch (ex.getErrorCode()) {
            case "OTP_RESEND_COOLDOWN", "RATE_LIMIT_EXCEEDED" -> HttpStatus.TOO_MANY_REQUESTS;
            case "OTP_LOCKED" -> HttpStatus.LOCKED;
            case "USER_ALREADY_EXISTS" -> HttpStatus.CONFLICT;
            case "OTP_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST; // OTP_INVALID, OTP_EXPIRED, etc.
        };
        // Log the safe error code only - never any OTP value or other sensitive detail.
        log.info("OTP flow rejected: {} ({})", ex.getErrorCode(), request.getRequestURI());
        return build(status, ex.getErrorCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have access to this resource.", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                                HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST_BODY",
                "The request body is missing or malformed.", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "INVALID_STATE", ex.getMessage(), request);
    }

    @ExceptionHandler(NoteNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoteNotFound(NoteNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "NOTE_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(NoteAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleNoteAccessDenied(NoteAccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "NOTE_ACCESS_DENIED", ex.getMessage(), request);
    }

    @ExceptionHandler(AiGenerationException.class)
    public ResponseEntity<ErrorResponse> handleAiGeneration(AiGenerationException ex, HttpServletRequest request) {
        log.error("AI generation failed on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_GATEWAY, "AI_GENERATION_FAILED", ex.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}", request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. Please try again later.", request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String error, String message,
                                                 HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(status.value(), error, message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
