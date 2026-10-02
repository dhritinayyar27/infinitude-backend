package com.infinitude.email;

import com.infinitude.model.OtpPurpose;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class EmailServiceImplTests {
    private final JavaMailSender sender = mock(JavaMailSender.class);

    @ParameterizedTest
    @EnumSource(OtpPurpose.class)
    void sendsOtpOnlyByEmail(OtpPurpose purpose, CapturedOutput output) {
        EmailServiceImpl service = new EmailServiceImpl(provider(sender), "smtp.example.com", "sender@example.com");

        service.sendOtpEmail("recipient@example.com", "123456", purpose);

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertEquals("sender@example.com", message.getValue().getFrom());
        assertArrayEquals(new String[]{"recipient@example.com"}, message.getValue().getTo());
        assertTrue(message.getValue().getText().contains("123456"));
        assertTrue(message.getValue().getSubject().contains(purpose.name().toLowerCase()));
        assertFalse(output.getAll().contains("123456"));
        assertFalse(output.getAll().contains("recipient@example.com"));
    }

    @Test
    void smtpFailureDoesNotLogOtpOrRawError(CapturedOutput output) {
        doThrow(new MailSendException("Sensitive SMTP response: 123456 recipient@example.com"))
                .when(sender).send(any(SimpleMailMessage.class));
        EmailServiceImpl service = new EmailServiceImpl(provider(sender), "smtp.example.com", "sender@example.com");

        assertDoesNotThrow(() -> service.sendOtpEmail("recipient@example.com", "123456", OtpPurpose.LOGIN));

        assertTrue(output.getAll().contains("Failed to send OTP email"));
        assertFalse(output.getAll().contains("123456"));
        assertFalse(output.getAll().contains("Sensitive SMTP response"));
        assertFalse(output.getAll().contains("recipient@example.com"));
    }

    @Test
    void missingMailConfigurationFailsAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> new EmailServiceImpl(provider(null), "smtp.example.com", "sender@example.com"));
        assertThrows(IllegalStateException.class,
                () -> new EmailServiceImpl(provider(sender), " ", "sender@example.com"));
        assertThrows(IllegalStateException.class,
                () -> new EmailServiceImpl(provider(sender), "smtp.example.com", ""));
        verifyNoInteractions(sender);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> provider(JavaMailSender mailSender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        return provider;
    }
}