package com.infinitude.email;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VercelEmailRelayTests {
    private static final String SECRET = "fixture-secret-at-least-32-bytes-long";
    private static final String URL = "https://frontend.example.com/api/send-otp";
    private final HttpClient client = mock(HttpClient.class);

    @Test
    void signsRecipientTimestampRequestIdAndExactMimeBytes() throws Exception {
        stubResponse(204);
        byte[] mime = "From: sender@example.com\r\n\r\n123456".getBytes(StandardCharsets.UTF_8);

        new VercelEmailRelay(URL, SECRET, client).send("user@example.com", mime);

        ArgumentCaptor<HttpRequest> capture = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(capture.capture(), any());
        HttpRequest request = capture.getValue();
        assertEquals("POST", request.method());
        assertEquals(URL, request.uri().toString());
        assertEquals(Duration.ofSeconds(25), request.timeout().orElseThrow());
        assertEquals(mime.length, request.bodyPublisher().orElseThrow().contentLength());
        String prefix = request.headers().firstValue("X-OTP-Timestamp").orElseThrow() + "\n"
                + request.headers().firstValue("X-OTP-Request-Id").orElseThrow() + "\n"
                + "user@example.com\n";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(prefix.getBytes(StandardCharsets.UTF_8));
        assertEquals(HexFormat.of().formatHex(mac.doFinal(mime)),
                request.headers().firstValue("X-OTP-Signature").orElseThrow());
        assertTrue(request.headers().firstValue("Authorization").isEmpty());
    }

    @Test
    void nonSuccessResponseIsNotTreatedAsDelivery() throws Exception {
        for (int status : new int[]{200, 401, 409, 502, 503}) {
            stubResponse(status);
            assertThrows(MailSendException.class,
                    () -> new VercelEmailRelay(URL, SECRET, client).send("user@example.com", new byte[]{1}));
        }
    }

    @Test
    void connectionFailureOmitsSensitiveCause() throws Exception {
        when(client.send(any(), any())).thenThrow(new IOException("123456 user@example.com"));
        MailSendException error = assertThrows(MailSendException.class,
                () -> new VercelEmailRelay(URL, SECRET, client).send("user@example.com", new byte[]{1}));
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("123456"));
    }

    @Test
    void interruptionRestoresThreadFlag() throws Exception {
        when(client.send(any(), any())).thenThrow(new InterruptedException());
        try {
            assertThrows(MailSendException.class,
                    () -> new VercelEmailRelay(URL, SECRET, client).send("user@example.com", new byte[]{1}));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void invalidRelaySettingsFailAtStartup() {
        for (String url : new String[]{"", "http://example.com", "https://user:pass@example.com", "invalid url"}) {
            assertThrows(IllegalStateException.class, () -> new VercelEmailRelay(url, SECRET, client));
        }
        assertThrows(IllegalStateException.class, () -> new VercelEmailRelay(URL, "short", client));
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(int status) throws Exception {
        HttpResponse<Object> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(client.send(any(), any())).thenReturn(response);
    }
}
