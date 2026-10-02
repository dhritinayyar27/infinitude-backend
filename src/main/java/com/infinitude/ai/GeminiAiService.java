package com.infinitude.ai;

import tools.jackson.databind.ObjectMapper;
import com.infinitude.ai.model.*;
import com.infinitude.ai.prompt.TocPromptBuilder;
import com.infinitude.exception.AiGenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Gemini REST implementation of {@link AiService}.
 *
 * <p>Model fallback: if the preferred model returns 503 (Service Unavailable / overloaded),
 * the remaining models from the server fallback list are tried in list
 * order until one succeeds. Any other error (429 quota, 403 invalid key, parse failure) is
 * propagated immediately without trying other models — those are key-level or request-level
 * failures, not transient model-availability issues.</p>
 */
@Service
public class GeminiAiService implements AiService {

    private static final Logger log = LoggerFactory.getLogger(GeminiAiService.class);
    private static final String MODEL_UNAVAILABLE = "MODEL_UNAVAILABLE";
    private static final List<String> FALLBACK_MODELS = List.of("gemini-2.5-flash", "gemini-2.5-flash-lite");

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final TocPromptBuilder tocPromptBuilder;

    public GeminiAiService(RestTemplate restTemplate,
                           @Value("${gemini.api.base-url}") String baseUrl,
                           ObjectMapper objectMapper,
                           TocPromptBuilder tocPromptBuilder) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.tocPromptBuilder = tocPromptBuilder;
    }

    @Override
    public TocAiResponse generateTableOfContents(String topic, String difficulty,
                                                  String apiKey, String preferredModel) {
        String prompt = tocPromptBuilder.build(topic, difficulty);
        GeminiRequest request = buildRequest(prompt, 0.4, "application/json");

        try {
            String responseText = callWithFallback(request, preferredModel, apiKey);
            return objectMapper.readValue(responseText, TocAiResponse.class);
        } catch (AiGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new AiGenerationException("AI_GENERATION_FAILED: Failed to parse TOC response: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Fallback-aware dispatcher
    // -------------------------------------------------------------------------

    /**
     * Calls Gemini with the preferred model, falling back to the remaining models in
    * the server fallback order if a 503 is returned.
     * Non-503 errors (quota, invalid key, parse failure) are propagated immediately.
     */
    private String callWithFallback(GeminiRequest request, String preferredModel, String apiKey) {
        // Build try order: preferred first, then the rest of the list in declared order
        List<String> tryOrder = new ArrayList<>();
        tryOrder.add(preferredModel);
        for (String fallbackModel : FALLBACK_MODELS) {
            if (!fallbackModel.equals(preferredModel)) {
                tryOrder.add(fallbackModel);
            }
        }

        AiGenerationException lastUnavailable = null;

        for (String model : tryOrder) {
            try {
                String url = buildUrl(model);
                String result = callGemini(url, request, apiKey);
                if (!model.equals(preferredModel)) {
                    log.info("Gemini fallback succeeded with model={} (preferred={} was unavailable)",
                            model, preferredModel);
                }
                return result;
            } catch (AiGenerationException e) {
                if (MODEL_UNAVAILABLE.equals(e.getMessage())) {
                    log.warn("Model {} returned 503 (unavailable), trying next fallback", model);
                    lastUnavailable = e;
                } else {
                    // quota exceeded, invalid key, parse error, etc. — don't mask with a fallback
                    throw e;
                }
            }
        }

        throw new AiGenerationException(
                "AI_GENERATION_FAILED: All " + tryOrder.size() + " models returned 503 (service unavailable). "
                + "This is likely a temporary Gemini overload — please retry shortly.",
                lastUnavailable);
    }

    // -------------------------------------------------------------------------
    // Single-model HTTP call
    // -------------------------------------------------------------------------

    private String callGemini(String url, GeminiRequest request, String apiKey) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("x-goog-api-key", apiKey);
            ResponseEntity<GeminiResponse> responseEntity =
                    restTemplate.postForEntity(url, new HttpEntity<>(request, headers), GeminiResponse.class);

            GeminiResponse response = responseEntity.getBody();
            if (response == null
                    || response.candidates == null
                    || response.candidates.isEmpty()
                    || response.candidates.get(0).content == null
                    || response.candidates.get(0).content.parts == null
                    || response.candidates.get(0).content.parts.isEmpty()) {
                throw new AiGenerationException("AI_GENERATION_FAILED: Empty or unexpected response from Gemini API");
            }

            return response.candidates.get(0).content.parts.get(0).text;

        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 429) throw new AiGenerationException("QUOTA_EXCEEDED");
            if (status == 403 || status == 401) throw new AiGenerationException("INVALID_API_KEY");
            throw new AiGenerationException("AI_GENERATION_FAILED: HTTP " + status);

        } catch (HttpServerErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 503) throw new AiGenerationException(MODEL_UNAVAILABLE);
            throw new AiGenerationException("AI_GENERATION_FAILED: HTTP " + status);

        } catch (AiGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new AiGenerationException("AI_GENERATION_FAILED: Unable to reach Gemini API.");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private GeminiRequest buildRequest(String prompt, double temperature, String responseMimeType) {
        GeminiPart part = new GeminiPart();
        part.text = prompt;

        GeminiContent content = new GeminiContent();
        content.parts = List.of(part);

        GeminiGenerationConfig config = new GeminiGenerationConfig();
        config.temperature = temperature;
        if (responseMimeType != null) {
            config.responseMimeType = responseMimeType;
        }

        GeminiRequest request = new GeminiRequest();
        request.contents = List.of(content);
        request.generationConfig = config;

        return request;
    }

    private String buildUrl(String model) {
        return baseUrl + "/v1beta/models/" + model + ":generateContent";
    }
}
