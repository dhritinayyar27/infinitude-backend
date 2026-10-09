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
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import java.util.Properties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

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

    @Test
    void vercelModeDoesNotRequireOrResolveSmtpSender() {
        ObjectProvider<JavaMailSender> mailProvider = provider(null);
        assertDoesNotThrow(() -> new EmailServiceImpl(mailProvider, "", "sender@example.com", 300,
                "vercel", "https://frontend.example.com/api/send-otp",
                "fixture-secret-at-least-32-bytes-long"));
        verify(mailProvider, never()).getIfAvailable();
    }

    @Test
    void springStartsVercelDeliveryWithoutMailSenderBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(EmailServiceImpl.class)
                .withPropertyValues(
                        "infinitude.email.delivery=vercel",
                        "infinitude.email.relay-url=https://frontend.example.com/api/send-otp",
                        "infinitude.email.relay-secret=fixture-secret-at-least-32-bytes-long",
                        "MAIL_FROM=sender@example.com")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(EmailService.class));
                    assertTrue(context.getBeansOfType(JavaMailSender.class).isEmpty());
                });
    }

    @Test
    void invalidDeliveryModeFailsAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> new EmailServiceImpl(provider(sender), "smtp.example.com", "sender@example.com", 300,
                        "unknown", "", ""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void vercelDeliveryPreservesTemplateLogoAndPrivacyWithoutSmtp(CapturedOutput output) throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpClient.Builder builder = mock(HttpClient.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(client);
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(204);
        when(client.send(any(), any())).thenReturn(response);
        ObjectProvider<JavaMailSender> mailProvider = provider(null);
        try (var clients = mockStatic(HttpClient.class)) {
            clients.when(HttpClient::newBuilder).thenReturn(builder);
            var service = new EmailServiceImpl(mailProvider, "", "sender@example.com", 300,
                    "vercel", "https://frontend.example.com/api/send-otp",
                    "fixture-secret-at-least-32-bytes-long");

            service.sendOtpEmail("recipient@example.com", "123456", OtpPurpose.SIGNUP);
        }

        ArgumentCaptor<HttpRequest> capture = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(capture.capture(), any());
        HttpRequest request = capture.getValue();
        byte[] content = requestBody(request);
        assertTrue(content.length < 256 * 1024);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(content));
        assertEquals("sender@example.com", message.getFrom()[0].toString());
        assertEquals("recipient@example.com", message.getAllRecipients()[0].toString());
        assertTrue(findHtml(message).contains("123456"));
        assertTrue(findHtml(message).contains("5 minutes"));
        assertTrue(hasInlineLogo(message));
        verify(mailProvider, never()).getIfAvailable();
        assertTrue(output.getAll().contains("OTP email dispatched"));
        assertFalse(output.getAll().contains("123456"));
        assertFalse(output.getAll().contains("recipient@example.com"));
    }

    private byte[] requestBody(HttpRequest request) {
        var bytes = new ByteArrayOutputStream();
        var complete = new CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer buffer) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }

            @Override
            public void onError(Throwable error) {
                complete.completeExceptionally(error);
            }

            @Override
            public void onComplete() {
                complete.complete(bytes.toByteArray());
            }
        });
        return complete.join();
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