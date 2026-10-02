package com.infinitude.ai;

import com.infinitude.ai.model.*;
import com.infinitude.ai.parser.GeminiResponseParser;
import com.infinitude.ai.prompt.TocPromptBuilder;
import com.infinitude.config.GeminiConfiguration;
import com.infinitude.config.GeminiKeyPool;
import com.infinitude.exception.AiGenerationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/** REST-only adapter. All failover state belongs to a single generation request. */
@Service
public class GeminiAiService implements AiService {
    private final GeminiRestClient client;
    private final GeminiResponseParser parser;
    private final TocPromptBuilder tocPromptBuilder;

    public GeminiAiService(RestTemplate restTemplate,
                           @Value("${gemini.api.base-url}") String baseUrl,
                           ObjectMapper objectMapper,
                           TocPromptBuilder tocPromptBuilder) {
        this.client = new GeminiRestClient(restTemplate, baseUrl, objectMapper);
        this.parser = new GeminiResponseParser(objectMapper);
        this.tocPromptBuilder = tocPromptBuilder;
    }

    @Override
    public TocAiResponse generateTableOfContents(String topic, String difficulty,
                                                 String apiKey, String preferredModel) {
        GeminiRequest request = buildRequest(tocPromptBuilder.build(topic, difficulty));
        List<String> keys = GeminiKeyPool.parse(apiKey).keys();
        List<String> models = GeminiConfiguration.modelCandidates(preferredModel);
        if (keys.isEmpty()) {
            throw new AiGenerationException("INVALID_API_KEY");
        }

        // Indices only advance, never reset: each pair is attempted at most once.
        // Key rejection/quota skips that key for this request; unavailability skips
        // that model. At most K + M - 1 calls (and therefore at most K * M).
        int keyIndex = 0;
        int modelIndex = 0;
        while (true) {
            GeminiRestClient.Result result =
                    client.generate(models.get(modelIndex), keys.get(keyIndex), request);
            switch (result.failure()) {
                case NONE -> {
                    // A malformed success is terminal, not a key/model failover signal.
                    return parser.parseToc(result.response());
                }
                case MODEL_UNAVAILABLE -> {
                    if (++modelIndex == models.size()) {
                        throw new AiGenerationException(
                                "AI_GENERATION_FAILED: All candidate models are unavailable or unsupported. "
                                        + "Please retry later or select a supported model.");
                    }
                }
                case INVALID_KEY, QUOTA -> {
                    if (++keyIndex == keys.size()) {
                        throw new AiGenerationException(result.failure() == GeminiRestClient.Failure.QUOTA
                                ? "QUOTA_EXCEEDED" : "INVALID_API_KEY");
                    }
                    // Bounded credential failover only. Keys may share quota; no
                    // assumption that changing credentials increases quota.
                }
            }
        }
    }

    private GeminiRequest buildRequest(String prompt) {
        GeminiPart part = new GeminiPart();
        part.text = prompt;
        GeminiContent content = new GeminiContent();
        content.parts = List.of(part);
        GeminiGenerationConfig config = new GeminiGenerationConfig();
        config.temperature = 0.4;
        config.responseMimeType = "application/json";
        GeminiRequest request = new GeminiRequest();
        request.contents = List.of(content);
        request.generationConfig = config;
        return request;
    }
}
