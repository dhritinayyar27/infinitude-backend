package com.infinitude.security;

import com.infinitude.exception.InvalidOtpException;
import com.infinitude.exception.OtpCooldownException;
import com.infinitude.exception.OtpExpiredException;
import com.infinitude.exception.OtpLockedException;
import com.infinitude.exception.OtpNotFoundException;
import com.infinitude.model.OtpPurpose;
import com.infinitude.model.OtpVerification;
import com.infinitude.repository.OtpVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OtpServiceTests {
    private final OtpVerificationRepository repository = mock(OtpVerificationRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final OtpService service = new OtpService(repository, encoder, 90, 5, 30);
    private final OtpVerification record = new OtpVerification();

    @BeforeEach
    void prepareRecord() {
        record.setExpiresAt(Instant.now().plusSeconds(90));
        record.setOtpHash("encoded-secret");
        when(repository.findTopByEmailAndPurposeOrderByCreatedAtDesc("recipient@example.test", OtpPurpose.LOGIN))
                .thenReturn(Optional.of(record));
    }

    @Test
    void incorrectCodeIncrementsAttempts() {
        when(encoder.matches("000000", record.getOtpHash())).thenReturn(false);
        assertThrows(InvalidOtpException.class,
                () -> service.verifyOtp("recipient@example.test", OtpPurpose.LOGIN, "000000"));
        assertEquals(1, record.getAttemptCount());
        verify(repository).save(record);
    }

    @Test
    void fifthIncorrectCodeLocksFurtherAttempts() {
        record.setAttemptCount(4);
        assertThrows(OtpLockedException.class,
                () -> service.verifyOtp("recipient@example.test", OtpPurpose.LOGIN, "000000"));
        assertEquals(5, record.getAttemptCount());
        clearInvocations(encoder);
        assertThrows(OtpLockedException.class,
                () -> service.verifyOtp("recipient@example.test", OtpPurpose.LOGIN, "000000"));
        verifyNoInteractions(encoder);
    }

    @Test
    void expiredCodeNeverAuthenticates() {
        record.setExpiresAt(Instant.now().minusSeconds(1));
        assertThrows(OtpExpiredException.class,
                () -> service.verifyOtp("recipient@example.test", OtpPurpose.LOGIN, "000000"));
        verifyNoInteractions(encoder);
        verify(repository, never()).save(any());
    }

    @Test
    void validCodeIsSingleUse() {
        when(encoder.matches("000000", record.getOtpHash())).thenReturn(true);
        service.verifyOtp("recipient@example.test", OtpPurpose.LOGIN, "000000");
        assertTrue(record.isUsed());
        assertThrows(OtpNotFoundException.class,
                () -> service.verifyOtp("recipient@example.test", OtpPurpose.LOGIN, "000000"));
        verify(repository).save(record);
    }

    @Test
    void resendCooldownRemainsServerEnforced() {
        record.setLastSentAt(Instant.now());
        assertThrows(OtpCooldownException.class,
                () -> service.generateAndPersistOtp("recipient@example.test", OtpPurpose.LOGIN));
        verifyNoInteractions(encoder);
        verify(repository, never()).save(any());
    }

    @Test
    void generationPersistsOnlyHashAndConfiguredExpiry() {
        when(repository.findTopByEmailAndPurposeOrderByCreatedAtDesc("new@example.test", OtpPurpose.LOGIN))
                .thenReturn(Optional.empty());
        when(encoder.encode(anyString())).thenReturn("encoded-secret");
        Instant before = Instant.now();
        String generated = service.generateAndPersistOtp("new@example.test", OtpPurpose.LOGIN);
        var captor = org.mockito.ArgumentCaptor.forClass(OtpVerification.class);
        verify(repository).save(captor.capture());
        assertTrue(generated.matches("\\d{6}"));
        assertEquals("encoded-secret", captor.getValue().getOtpHash());
        assertFalse(generated.equals(captor.getValue().getOtpHash()));
        assertFalse(captor.getValue().getExpiresAt().isBefore(before.plusSeconds(90)));
        assertFalse(captor.getValue().isUsed());
        assertEquals(0, captor.getValue().getAttemptCount());
    }
}
