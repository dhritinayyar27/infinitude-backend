package com.infinitude.email;

import com.infinitude.model.OtpPurpose;
import jakarta.mail.Part;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class EmailServiceImplTests {
    private final JavaMailSender sender = mock(JavaMailSender.class);

    @ParameterizedTest
    @EnumSource(OtpPurpose.class)
    void sendsOtpOnlyByEmail(OtpPurpose purpose, CapturedOutput output) throws Exception {
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        EmailServiceImpl service = new EmailServiceImpl(provider(sender), "smtp.example.com", "sender@example.com", 300);

        service.sendOtpEmail("recipient@example.com", "123456", purpose);

        ArgumentCaptor<MimeMessage> message = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(message.capture());
        message.getValue().saveChanges();
        assertEquals("sender@example.com", message.getValue().getFrom()[0].toString());
        assertEquals("recipient@example.com", message.getValue().getAllRecipients()[0].toString());
        String html = findHtml(message.getValue());
        assertTrue(html.contains("123456"));
        assertTrue(html.contains("cid:infinitude-logo"));
        assertTrue(html.contains("#863bff"));
        assertTrue(html.contains("5 minutes"));
        assertTrue(html.contains("Do not share this OTP with anyone."));
        assertTrue(html.contains("max-width: 480px"));
        assertTrue(hasInlineLogo(message.getValue()));
        assertTrue(message.getValue().getSubject().contains(purpose.name().toLowerCase()));
        assertFalse(output.getAll().contains("123456"));
        assertFalse(output.getAll().contains("recipient@example.com"));
    }

    @Test
    void smtpFailureDoesNotLogOtpOrRawError(CapturedOutput output) {
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("Sensitive SMTP response: 123456 recipient@example.com"))
                .when(sender).send(any(MimeMessage.class));
        EmailServiceImpl service = new EmailServiceImpl(provider(sender), "smtp.example.com", "sender@example.com", 300);

        assertDoesNotThrow(() -> service.sendOtpEmail("recipient@example.com", "123456", OtpPurpose.LOGIN));

        assertTrue(output.getAll().contains("Failed to send OTP email"));
        assertFalse(output.getAll().contains("123456"));
        assertFalse(output.getAll().contains("Sensitive SMTP response"));
        assertFalse(output.getAll().contains("recipient@example.com"));
    }

    @Test
    void missingMailConfigurationFailsAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> new EmailServiceImpl(provider(null), "smtp.example.com", "sender@example.com", 300));
        assertThrows(IllegalStateException.class,
                () -> new EmailServiceImpl(provider(sender), " ", "sender@example.com", 300));
        assertThrows(IllegalStateException.class,
                () -> new EmailServiceImpl(provider(sender), "smtp.example.com", "", 300));
        verifyNoInteractions(sender);
    }

    @Test
    void usesConfiguredExpiryAndEscapesDynamicContent() throws Exception {
        var message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        var service = new EmailServiceImpl(provider(sender), "smtp.example.com", "sender@example.com", 90);
        service.sendOtpEmail("recipient@example.com", "<script>", OtpPurpose.LOGIN);
        message.saveChanges();
        String html = findHtml(message);
        assertTrue(html.contains("90 seconds"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("{{"));
    }

    @Test
    void rejectsInvalidExpiryConfiguration() {
        assertThrows(IllegalStateException.class,
                () -> new EmailServiceImpl(provider(sender), "smtp.example.com", "sender@example.com", 0));
    }

    private String findHtml(Part part) throws Exception {
        if (part.isMimeType("text/html")) return (String) part.getContent();
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                String html = findHtml(multipart.getBodyPart(i));
                if (!html.isEmpty()) return html;
            }
        }
        return "";
    }

    private boolean hasInlineLogo(Part part) throws Exception {
        if (part.isMimeType("image/png")) {
            assertEquals("<infinitude-logo>", part.getHeader("Content-ID")[0]);
            assertEquals(Part.INLINE, part.getDisposition());
            assertTrue(part.getInputStream().readAllBytes().length > 1000);
            return true;
        }
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                if (hasInlineLogo(multipart.getBodyPart(i))) return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> provider(JavaMailSender mailSender) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mailSender);
        return provider;
    }
}