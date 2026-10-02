package com.infinitude.ai.parser;

import com.infinitude.ai.model.GeminiResponse;
import com.infinitude.ai.model.TocAiResponse;
import com.infinitude.exception.AiGenerationException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Validates the provider envelope and TOC before exposing it to business code. */
public final class GeminiResponseParser {
    private final ObjectMapper objectMapper;

    public GeminiResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TocAiResponse parseToc(GeminiResponse response) {
        if (response == null || response.candidates == null || response.candidates.isEmpty()) {
            throw malformed();
        }
        var candidate = response.candidates.get(0);
        if (candidate == null || candidate.content == null || candidate.content.parts == null
                || candidate.content.parts.isEmpty()) {
            throw malformed();
        }
        var part = candidate.content.parts.get(0);
        if (part == null || part.text == null || part.text.isBlank()) {
            throw malformed();
        }
        try {
            TocAiResponse toc = objectMapper.readValue(part.text, TocAiResponse.class);
            if (toc == null || toc.getSections() == null || toc.getSections().isEmpty()
                    || toc.getSections().stream().anyMatch(section ->
                    section == null || section.getTitle() == null || section.getTitle().isBlank()
                            || (section.getSubsections() != null && section.getSubsections().stream()
                            .anyMatch(title -> title == null || title.isBlank())))) {
                throw malformed();
            }
            return toc;
        } catch (JacksonException e) {
            throw malformed();
        }
    }

    private AiGenerationException malformed() {
        return new AiGenerationException("AI_GENERATION_FAILED: Empty or malformed TOC response from Gemini API.");
    }
}
