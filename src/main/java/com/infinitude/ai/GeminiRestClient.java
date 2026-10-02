package com.infinitude.ai;

import com.infinitude.ai.model.GeminiRequest;
import com.infinitude.ai.model.GeminiResponse;
import com.infinitude.exception.AiGenerationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The only component that constructs Gemini HTTP requests. Never retains credentials. */
final class GeminiRestClient {
    enum Failure { NONE, MODEL_UNAVAILABLE, INVALID_KEY, QUOTA }
    record Result(GeminiResponse response, Failure failure) { }

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final ObjectMapper objectMapper;

    GeminiRestClient(RestTemplate restTemplate, String baseUrl, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
    }

    Result generate(String model, String key, GeminiRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", key);
        try {
            GeminiResponse response = restTemplate.postForEntity(
                    baseUrl + "/v1beta/models/" + model + ":generateContent",
                    new HttpEntity<>(request, headers), GeminiResponse.class).getBody();
            return new Result(response, Failure.NONE);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 503 || status == 404) {
                return new Result(null, Failure.MODEL_UNAVAILABLE);
            }
            if (status == 401 || status == 403 || (status == 400 && isInvalidKey(e))) {
                return new Result(null, Failure.INVALID_KEY);
            }
            if (status == 429) {
                return new Result(null, Failure.QUOTA);
            }
            // Do not retain the cause: provider bodies, headers and request data
            // can contain secrets, including in nested exception messages.
            throw new AiGenerationException("AI_GENERATION_FAILED: HTTP " + status);
        } catch (RestClientException | JacksonException e) {
            throw new AiGenerationException("AI_GENERATION_FAILED: Unable to read or reach Gemini API.");
        }
    }

    private boolean isInvalidKey(RestClientResponseException exception) {
        try {
            JsonNode body = objectMapper.readTree(exception.getResponseBodyAsString());
            if (body == null) {
                return false;
            }
            JsonNode error = body.path("error");
            if ("UNAUTHENTICATED".equals(error.path("status").asText())) {
                return true;
            }
            JsonNode details = error.path("details");
            if (details.isArray()) {
                for (JsonNode detail : details) {
                    if ("type.googleapis.com/google.rpc.ErrorInfo".equals(detail.path("@type").asText())) {
                        String reason = detail.path("reason").asText();
                        if ("API_KEY_INVALID".equals(reason) || "API_KEY_EXPIRED".equals(reason)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (JacksonException e) {
            // Unparseable error bodies cannot establish credential rejection.
            return false;
        }
    }
}
