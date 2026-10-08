package com.infinitude.ai;

import com.infinitude.ai.model.*;
import com.infinitude.ai.parser.GeminiResponseParser;
import com.infinitude.ai.prompt.TocPromptBuilder;
import com.infinitude.ai.prompt.NotesGenerationGuide;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import com.infinitude.config.GeminiConfiguration;
import com.infinitude.config.GeminiKeyPool;
import com.infinitude.exception.AiGenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** REST-only adapter. All failover state belongs to a single generation request. */
@Service
public class GeminiAiService implements AiService {
    private static final Logger log = LoggerFactory.getLogger(GeminiAiService.class);
    private static final Set<String> LOW_THINKING_MODELS = Set.of(
            "gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash",
            "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite-preview");
    private final GeminiRestClient client;
    private final GeminiRestClient notesClient;
    private final GeminiResponseParser parser;
    private final TocPromptBuilder tocPromptBuilder;

    public GeminiAiService(RestTemplate restTemplate,
                           @Value("${gemini.api.base-url}") String baseUrl,
                           ObjectMapper objectMapper,
                           TocPromptBuilder tocPromptBuilder) {
        this(restTemplate, baseUrl, objectMapper, tocPromptBuilder, restTemplate);
    }

    @Autowired
    public GeminiAiService(RestTemplate restTemplate,
                           @Value("${gemini.api.base-url}") String baseUrl,
                           ObjectMapper objectMapper, TocPromptBuilder tocPromptBuilder,
                           @Qualifier("notesRestTemplate") RestTemplate notesRestTemplate) {
        this.client = new GeminiRestClient(restTemplate, baseUrl, objectMapper);
        this.notesClient = new GeminiRestClient(notesRestTemplate, baseUrl, objectMapper);
        this.parser = new GeminiResponseParser(objectMapper);
        this.tocPromptBuilder = tocPromptBuilder;
    }

    @Override
    public TocAiResponse generateTableOfContents(String topic, String difficulty,
                                                 String apiKey, String preferredModel) {
        String prompt = tocPromptBuilder.build(topic, difficulty);
        return parser.parseToc(generate(prompt, apiKey, preferredModel, false));
    }

    @Override
    public SectionAiResponse generateSection(String prompt, String apiKey, String preferredModel) {
        return parser.parseSection(generate(prompt, apiKey, preferredModel, true));
    }

    private GeminiResponse generate(String prompt, String apiKey, String preferredModel, boolean notes) {
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
        int attempts = 0;
        String requestId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        try {
            while (true) {
                String model = models.get(modelIndex);
                long attemptStarted = System.nanoTime();
                attempts++;
                GeminiRestClient.Result result;
                try {
                    result = (notes ? notesClient : client).generate(model, keys.get(keyIndex),
                            buildRequest(prompt, model, notes));
                } catch (AiGenerationException e) {
                    log.warn("Gemini request={} attempt={} model={} outcome=terminal_failure elapsedMs={}",
                            requestId, attempts, model, elapsedMs(attemptStarted));
                    throw e;
                }
                log.info("Gemini request={} attempt={} model={} outcome={} elapsedMs={}",
                        requestId, attempts, model, result.failure(), elapsedMs(attemptStarted));
                switch (result.failure()) {
                    case NONE -> {
                        // A malformed success is terminal, not a key/model failover signal.
                        return result.response();
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
        } finally {
            log.info("Gemini request={} attempts={} totalElapsedMs={}",
                    requestId, attempts, elapsedMs(started));
        }
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private GeminiRequest buildRequest(String prompt, String model, boolean notes) {
        GeminiPart part = new GeminiPart();
        part.text = prompt;
        GeminiContent content = new GeminiContent();
        content.parts = List.of(part);
        GeminiGenerationConfig config = new GeminiGenerationConfig();
        config.temperature = 0.4;
        config.responseMimeType = "application/json";
        if (notes) {
            config.maxOutputTokens = 16384;
        } else if (LOW_THINKING_MODELS.contains(model)) {
            config.thinkingConfig = new GeminiThinkingConfig();
            config.thinkingConfig.thinkingLevel = "LOW";
        } else if ("gemini-2.5-flash".equals(model) || "gemini-2.5-flash-lite".equals(model)) {
            config.thinkingConfig = new GeminiThinkingConfig();
            config.thinkingConfig.thinkingBudget = 0;
        }
        GeminiRequest request = new GeminiRequest();
        request.contents = List.of(content);
        request.generationConfig = config;
        if (notes) {
            GeminiPart instruction = new GeminiPart();
            instruction.text = NotesGenerationGuide.systemPrompt();
            request.systemInstruction = new GeminiContent();
            request.systemInstruction.parts = List.of(instruction);
        }
        return request;
    }
}
