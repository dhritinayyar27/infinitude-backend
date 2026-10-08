package com.infinitude.config;

import com.sun.net.httpserver.HttpServer;
import com.infinitude.ai.GeminiAiService;
import com.infinitude.ai.prompt.TocPromptBuilder;
import com.infinitude.exception.AiGenerationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class RestTemplateConfigTests {
    @Test
    void rejectsUnboundedTimeoutConfiguration() {
        RestTemplateConfig config = new RestTemplateConfig();
        assertThrows(IllegalArgumentException.class, () -> config.restTemplate(0, 100));
        assertThrows(IllegalArgumentException.class, () -> config.restTemplate(100, -1));
        assertThrows(IllegalArgumentException.class, () -> config.notesRestTemplate(100, 0));
    }

    @Test
    void notesReadTimeoutIsBoundedIndependentlyOfTocClient() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newSingleThreadExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try {
                RestTemplateConfig config = new RestTemplateConfig();
                var service = new GeminiAiService(config.restTemplate(500, 1000),
                        "http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper(),
                        new TocPromptBuilder(), config.notesRestTemplate(500, 100));
                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                    var error = assertThrows(AiGenerationException.class,
                            () -> service.generateSection("Topic data", "test-key", "gemini-2.5-flash"));
                    assertTrue(error.getMessage().startsWith("AI_GENERATION_TIMEOUT"));
                });
            } finally {
                server.stop(0);
                executor.shutdownNow();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stalledProviderIsStoppedByReadTimeout(boolean stallResponseBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newSingleThreadExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    if (stallResponseBody) {
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, 500);
                        exchange.getResponseBody().write('{');
                        exchange.getResponseBody().flush();
                    }
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            try {
                var template = new RestTemplateConfig().restTemplate(500, 100);
                String url = "http://127.0.0.1:" + server.getAddress().getPort();
                var service = new GeminiAiService(template, url, new ObjectMapper(), new TocPromptBuilder());
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                    AiGenerationException error = assertThrows(AiGenerationException.class,
                            () -> service.generateTableOfContents("Java", "BEGINNER", "test-key", null));
                    assertTrue(error.getMessage().startsWith("AI_GENERATION_TIMEOUT"), error.getMessage());
                    assertNull(error.getCause());
                });
            } finally {
                server.stop(0);
                executor.shutdownNow();
            }
        }
    }
}
