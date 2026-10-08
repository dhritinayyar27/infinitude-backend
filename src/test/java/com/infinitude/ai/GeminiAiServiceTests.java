package com.infinitude.ai;

import com.infinitude.ai.model.GeminiCandidate;
import com.infinitude.ai.model.GeminiContent;
import com.infinitude.ai.model.GeminiPart;
import com.infinitude.ai.model.GeminiResponse;
import com.infinitude.ai.model.TocAiResponse;
import com.infinitude.ai.prompt.TocPromptBuilder;
import com.infinitude.config.GeminiConfiguration;
import com.infinitude.exception.AiGenerationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GeminiAiServiceTests {
    private static final String BASE = "https://generativelanguage.googleapis.com";
    private static final String ERROR_BODY = "private-provider-body dummy-secret-key";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate template = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(template).build();
    private final GeminiAiService service = new GeminiAiService(template, BASE, mapper, new TocPromptBuilder());
    private final AtomicInteger attempts = new AtomicInteger();
    private final List<String> models = GeminiConfiguration.modelCandidates(null);

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {503, 404})
    void unavailableSwitchesModelAndKeepsKey(int status) {
        failure(models.get(0), "key-a", status, ERROR_BODY);
        success(models.get(1), "key-a", "Latest success");
        assertEquals("Latest success", generate("key-a,key-b", null).getSections().get(0).getTitle());
        assertEquals(2, attempts.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429})
    void credentialOrQuotaSwitchesKeyAndKeepsModel(int status) {
        failure(models.get(0), "key-a", status, ERROR_BODY);
        success(models.get(0), "key-b", "Latest success");
        assertEquals("Latest success", generate("key-a,key-b", null).getSections().get(0).getTitle());
        assertEquals(2, attempts.get());
    }

    @Test
    void mixed403Then503ThenSuccessRetainsEachAxis() {
        failure(models.get(0), "key-a", 403, ERROR_BODY);
        failure(models.get(0), "key-b", 503, ERROR_BODY);
        success(models.get(1), "key-b", "Recovered");
        assertEquals("Recovered", generate("key-a,key-b,key-c", null).getSections().get(0).getTitle());
        assertEquals(3, attempts.get());
    }

    @Test
    void skippedModelsAndKeysAreNeverRevisited() {
        failure(models.get(0), "key-a", 503, ERROR_BODY);
        failure(models.get(1), "key-a", 403, ERROR_BODY);
        failure(models.get(1), "key-b", 404, ERROR_BODY);
        failure(models.get(2), "key-b", 429, ERROR_BODY);
        success(models.get(2), "key-c", "Recovered");
        generate("key-a,key-b,key-c", null);
        assertEquals(5, attempts.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429})
    void keyExhaustionIsExactAndSafe(int status) {
        failure(models.get(0), "key-a", status, ERROR_BODY);
        failure(models.get(0), "key-b", status, ERROR_BODY);
        AiGenerationException error = assertThrows(AiGenerationException.class,
                () -> generate(" key-a, ,key-b, key-a ", null));
        assertEquals(status == 429 ? "QUOTA_EXCEEDED" : "INVALID_API_KEY", error.getMessage());
        assertSafe(error);
        assertEquals(2, attempts.get());
    }

    @Test
    void mixedKeyExhaustionPreservesLatestErrorCode() {
        failure(models.get(0), "key-a", 403, ERROR_BODY);
        failure(models.get(0), "key-b", 429, ERROR_BODY);
        assertEquals("QUOTA_EXCEEDED",
                assertThrows(AiGenerationException.class, () -> generate("key-a,key-b", null)).getMessage());
        assertEquals(2, attempts.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"gemini-3.8-flash", "gemini-2.5-flash", "custom-model"})
    void unavailablePreferredIsDeduplicatedAndAllModelsExhaustExactlyOnce(String preferred) {
        List<String> candidates = GeminiConfiguration.modelCandidates(preferred);
        for (int i = 0; i < candidates.size(); i++) {
            failure(candidates.get(i), "key-a", i % 2 == 0 ? 503 : 404, ERROR_BODY);
        }
        AiGenerationException error = assertThrows(AiGenerationException.class,
                () -> generate("key-a,key-b", preferred));
        assertTrue(error.getMessage().contains("All candidate models are unavailable or unsupported"));
        assertSafe(error);
        assertEquals(candidates.size(), attempts.get());
        assertEquals(candidates.size(), candidates.stream().distinct().count());
    }

    @Test
    void worstCaseMonotonePathHasKPlusMMinusOneAttempts() {
        failure(models.get(0), "key-a", 401, ERROR_BODY);
        failure(models.get(0), "key-b", 429, ERROR_BODY);
        for (String model : models) {
            failure(model, "key-c", 503, ERROR_BODY);
        }
        assertThrows(AiGenerationException.class, () -> generate("key-a,key-b,key-c", null));
        assertEquals(3 + models.size() - 1, attempts.get());
        assertTrue(attempts.get() <= 3 * models.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"API_KEY_INVALID", "API_KEY_EXPIRED", "UNAUTHENTICATED"})
    void structured400CanRejectKey(String reason) {
        String body = reason.equals("UNAUTHENTICATED")
                ? "{\"error\":{\"status\":\"UNAUTHENTICATED\"}}"
                : "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"details\":["
                    + "{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\""
                    + reason + "\"}]}}";
        failure(models.get(0), "key-a", 400, body);
        success(models.get(0), "key-b", "Recovered");
        generate("key-a,key-b", null);
        assertEquals(2, attempts.get());
    }

    @Test
    void structured400ExhaustionKeepsInvalidKeyCode() {
        failure(models.get(0), "key-a", 400,
                "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\","
                        + "\"reason\":\"API_KEY_EXPIRED\"}]}}");
        assertEquals("INVALID_API_KEY",
                assertThrows(AiGenerationException.class, () -> generate("key-a", null)).getMessage());
        assertEquals(1, attempts.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "API key invalid: dummy-secret-key",
            "{", "null", "{}",
            "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"message\":\"API_KEY_INVALID\"}}",
            "{\"error\":{\"reason\":\"API_KEY_EXPIRED\"}}",
            "{\"error\":{\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}",
            "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/other.ErrorInfo\",\"reason\":\"API_KEY_INVALID\"}]}}",
            "{\"error\":{\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\"BAD_REQUEST\"}]}}"
    })
    void genericOrUnreliable400IsTerminal(String body) {
        failure(models.get(0), "key-a", 400, body);
        AiGenerationException error = assertThrows(AiGenerationException.class,
                () -> generate("key-a,key-b", null));
        assertEquals("AI_GENERATION_FAILED: HTTP 400", error.getMessage());
        assertSafe(error);
        assertEquals(1, attempts.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 422, 500, 502, 504})
    void otherRequestFailuresTerminateWithoutLeakingBody(int status) {
        failure(models.get(0), "key-a", status, ERROR_BODY);
        AiGenerationException error = assertThrows(AiGenerationException.class,
                () -> generate("key-a,key-b", null));
        assertEquals("AI_GENERATION_FAILED: HTTP " + status, error.getMessage());
        assertSafe(error);
        assertEquals(1, attempts.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "null", "{", "{}", "{\"candidates\":[]}", "{\"candidates\":[null]}",
            "{\"candidates\":[{}]}", "{\"candidates\":[{\"content\":{}}]}",
            "{\"candidates\":[{\"content\":{\"parts\":[]}}]}",
            "{\"candidates\":[{\"content\":{\"parts\":[null]}}]}",
            "{\"candidates\":[{\"content\":{\"parts\":[{}]}}]}",
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"   \"}]}}]}"
    })
    void malformedOrEmptyEnvelopeDoesNotRetry(String body) {
        response(models.get(0), "key-a", 200, body);
        AiGenerationException error = assertThrows(AiGenerationException.class,
                () -> generate("key-a,key-b", null));
        assertTrue(error.getMessage().startsWith("AI_GENERATION_FAILED"));
        assertSafe(error);
        assertEquals(1, attempts.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "dummy-secret-key private-provider-body", "null", "{}", "{\"sections\":[]}",
            "{\"sections\":[null]}", "{\"sections\":[{}]}", "{\"sections\":[{\"title\":\" \"}]}",
            "{\"sections\":[{\"title\":\"Valid\",\"subsections\":[null]}]}"
    })
    void malformedTocIsTerminalAndParserMessagesAreSafe(String text) {
        response(models.get(0), "key-a", 200, envelope(text));
        AiGenerationException error = assertThrows(AiGenerationException.class,
                () -> generate("key-a,key-b", null));
        assertTrue(error.getMessage().startsWith("AI_GENERATION_FAILED"));
        assertSafe(error);
        assertEquals(1, attempts.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " , , "})
    void emptyKeyPoolMakesNoRequests(String keys) {
        assertEquals("INVALID_API_KEY",
                assertThrows(AiGenerationException.class, () -> generate(keys, null)).getMessage());
        assertEquals(0, attempts.get());
    }

    @Test
    void nullKeyPoolMakesNoRequests() {
        assertEquals("INVALID_API_KEY",
                assertThrows(AiGenerationException.class, () -> generate(null, null)).getMessage());
        assertEquals(0, attempts.get());
    }

    @Test
    void transportFailureIsNotMisclassifiedOrRetried() {
        RestTemplate mock = mock(RestTemplate.class);
        when(mock.postForEntity(anyString(), any(HttpEntity.class), eq(GeminiResponse.class)))
                .thenThrow(new ResourceAccessException(ERROR_BODY + " x-goog-api-key: key-a"));
        GeminiAiService isolated = new GeminiAiService(mock, BASE, mapper, new TocPromptBuilder());
        AiGenerationException error = assertThrows(AiGenerationException.class,
                () -> isolated.generateTableOfContents("Java", "BEGINNER", "key-a,key-b", null));
        assertTrue(error.getMessage().startsWith("AI_GENERATION_FAILED"));
        assertSafe(error);
        verify(mock, times(1)).postForEntity(anyString(), any(HttpEntity.class), eq(GeminiResponse.class));
    }

    @Test
    void timeoutHasExplicitSafeErrorAndDoesNotRotateKeysOrModels() {
        RestTemplate mock = mock(RestTemplate.class);
        when(mock.postForEntity(anyString(), any(HttpEntity.class), eq(GeminiResponse.class)))
                .thenThrow(new ResourceAccessException(ERROR_BODY, new HttpTimeoutException(ERROR_BODY)));
        GeminiAiService isolated = new GeminiAiService(mock, BASE, mapper, new TocPromptBuilder());

        AiGenerationException error = assertThrows(AiGenerationException.class,
                () -> isolated.generateTableOfContents("Java", "BEGINNER", "key-a,key-b", null));

        assertTrue(error.getMessage().startsWith("AI_GENERATION_TIMEOUT"));
        assertSafe(error);
        verify(mock, times(1)).postForEntity(anyString(), any(HttpEntity.class), eq(GeminiResponse.class));
    }

    @Test
    void lowThinkingIsRebuiltAsZeroBudgetWhenFallingBackTo25Flash() {
        List<String> candidates = GeminiConfiguration.modelCandidates(null);
        for (String model : candidates) {
            server.expect(requestTo(BASE + "/v1beta/models/" + model + ":generateContent"))
                    .andExpect(request -> {
                        var body = mapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request)
                                .getBodyAsString());
                        var thinking = body.path("generationConfig").path("thinkingConfig");
                        if ("gemini-2.5-flash".equals(model)) {
                            assertEquals(0, thinking.path("thinkingBudget").asInt());
                            assertFalse(thinking.has("thinkingLevel"));
                        } else if ("gemini-flash-latest".equals(model)) {
                            assertTrue(thinking.isMissingNode() || thinking.isNull());
                        } else {
                            assertEquals("LOW", thinking.path("thinkingLevel").asText());
                            assertFalse(thinking.has("thinkingBudget"));
                        }
                    })
                    .andRespond("gemini-2.5-flash".equals(model)
                            ? withSuccess(envelope("{\"sections\":[{\"title\":\"Basics\"}]}"), MediaType.APPLICATION_JSON)
                            : withStatus(HttpStatus.NOT_FOUND));
            if ("gemini-2.5-flash".equals(model)) {
                break;
            }
        }
        assertEquals("Basics", generate("key-a", null).getSections().getFirst().getTitle());
    }

    @Test
    void customModelDoesNotReceiveUnsupportedThinkingOptions() {
        server.expect(requestTo(BASE + "/v1beta/models/custom-model:generateContent"))
                .andExpect(request -> {
                    var body = mapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request)
                            .getBodyAsString());
                    var thinking = body.path("generationConfig").path("thinkingConfig");
                    assertTrue(thinking.isMissingNode() || thinking.isNull());
                })
                .andRespond(withSuccess(envelope("{\"sections\":[{\"title\":\"Basics\"}]}"), MediaType.APPLICATION_JSON));
        generate("key-a", "custom-model");
    }

    @Test
    void ignoresThoughtPartsAndCombinesMultipartJson() {
        response(models.getFirst(), "key-a", 200, """
                {"candidates":[{"finishReason":"STOP","content":{"parts":[
                  {"thought":true,"text":"Internal reasoning"},
                  {"text":"{\\\"sections\\\":["},
                  {"text":"{\\\"title\\\":\\\"Basics\\\"}]}"}
                ]}}]}
                """);
        assertEquals("Basics", generate("key-a", null).getSections().getFirst().getTitle());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MAX_TOKENS", "SAFETY", "RECITATION"})
    void incompleteResponseIsRejectedEvenIfItsTextIsValidJson(String finishReason) {
        response(models.getFirst(), "key-a", 200,
                envelope("{\"sections\":[{\"title\":\"Partial TOC\"}]}")
                        .replace("\"finishReason\":null", "\"finishReason\":\"" + finishReason + "\""));
        AiGenerationException error = assertThrows(AiGenerationException.class,
                () -> generate("key-a,key-b", null));
        assertTrue(error.getMessage().contains("did not complete"));
        assertSafe(error);
        assertEquals(1, attempts.get());
    }

    @Test
    void concurrentRequestsKeepIndependentFailoverState() throws Exception {
        RestTemplate mock = mock(RestTemplate.class);
        AtomicInteger callsA = new AtomicInteger();
        AtomicInteger callsB = new AtomicInteger();
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        when(mock.postForEntity(anyString(), any(HttpEntity.class), eq(GeminiResponse.class)))
                .thenAnswer(invocation -> {
                    String url = invocation.getArgument(0);
                    HttpEntity<?> entity = invocation.getArgument(1);
                    List<String> header = entity.getHeaders().get("x-goog-api-key");
                    assertNotNull(header);
                    assertEquals(1, header.size());
                    String key = header.get(0);
                    if (key.equals("request-a-first") || key.equals("request-b")) {
                        int count = key.equals("request-b") ? callsB.incrementAndGet() : callsA.incrementAndGet();
                        if (count == 1) {
                            barrier.await(5, TimeUnit.SECONDS);
                            throw org.springframework.web.client.HttpClientErrorException.create(
                                    key.equals("request-b") ? HttpStatus.NOT_FOUND : HttpStatus.FORBIDDEN,
                                    ERROR_BODY, null, ERROR_BODY.getBytes(), null);
                        }
                    } else {
                        assertEquals("request-a-second", key);
                        callsA.incrementAndGet();
                    }
                    assertTrue(url.contains("/" + (key.equals("request-b") ? models.get(1) : models.get(0)) + ":"));
                    return ResponseEntity.ok(mapper.readValue(
                            envelope("{\"sections\":[{\"title\":\"" + key + "\"}]}"), GeminiResponse.class));
                });
        GeminiAiService isolated = new GeminiAiService(mock, BASE, mapper, new TocPromptBuilder());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> isolated.generateTableOfContents(
                    "Java", "BEGINNER", "request-a-first,request-a-second", null));
            var b = executor.submit(() -> isolated.generateTableOfContents(
                    "Java", "BEGINNER", "request-b", null));
            assertEquals("request-a-second", a.get(10, TimeUnit.SECONDS).getSections().get(0).getTitle());
            assertEquals("request-b", b.get(10, TimeUnit.SECONDS).getSections().get(0).getTitle());
        }
        assertEquals(2, callsA.get());
        assertEquals(2, callsB.get());
        verify(mock, times(4)).postForEntity(anyString(), any(HttpEntity.class), eq(GeminiResponse.class));
    }

    private TocAiResponse generate(String keys, String preferred) {
        return service.generateTableOfContents("Java", "BEGINNER", keys, preferred);
    }

    private void failure(String model, String key, int status, String body) {
        response(model, key, status, body);
    }

    private void success(String model, String key, String title) {
        response(model, key, 200, envelope("{\"topic\":\"Java\",\"sections\":[{\"title\":\"" + title + "\"}]}"));
    }

    private void response(String model, String key, int status, String body) {
        server.expect(requestTo(BASE + "/v1beta/models/" + model + ":generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", key))
                .andExpect(request -> {
                    assertEquals(List.of(key), request.getHeaders().get("x-goog-api-key"));
                    assertFalse(key.contains(","));
                    assertFalse(request.getURI().toString().contains(key));
                    attempts.incrementAndGet();
                })
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private String envelope(String text) {
        GeminiPart part = new GeminiPart();
        part.text = text;
        GeminiContent content = new GeminiContent();
        content.parts = List.of(part);
        GeminiCandidate candidate = new GeminiCandidate();
        candidate.content = content;
        GeminiResponse response = new GeminiResponse();
        response.candidates = List.of(candidate);
        return mapper.writeValueAsString(response);
    }

    private void assertSafe(AiGenerationException error) {
        assertNull(error.getCause());
        assertFalse(error.toString().contains("private-provider-body"));
        assertFalse(error.toString().contains("dummy-secret-key"));
        assertFalse(error.toString().contains("key-a"));
        assertFalse(error.toString().contains("key-b"));
        assertFalse(error.toString().contains("x-goog-api-key"));
    }
}
