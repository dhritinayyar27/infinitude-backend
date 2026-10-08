package com.infinitude.ai.parser;

import com.infinitude.ai.model.GeminiResponse;
import com.infinitude.ai.model.TocAiResponse;
import com.infinitude.ai.model.SectionAiResponse;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import com.infinitude.exception.AiGenerationException;
import com.infinitude.exception.AiResponseValidationException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Validates the provider envelope and TOC before exposing it to business code. */
public final class GeminiResponseParser {
    private final ObjectMapper objectMapper;

    public GeminiResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TocAiResponse parseToc(GeminiResponse response) {
        String text = extractText(response);
        try {
            TocAiResponse toc = objectMapper.readValue(text, TocAiResponse.class);
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

    public SectionAiResponse parseSection(GeminiResponse response) {
        if (response != null && response.candidates != null && !response.candidates.isEmpty()
                && response.candidates.getFirst() != null
                && ("SAFETY".equals(response.candidates.getFirst().finishReason)
                    || "RECITATION".equals(response.candidates.getFirst().finishReason))) {
            throw new AiGenerationException("AI_GENERATION_BLOCKED: Gemini declined this topic response.");
        }
        try {
            SectionAiResponse section = objectMapper.readValue(extractText(response), SectionAiResponse.class);
            if (section == null || section.explanation() == null
                    || section.explanation().trim().split("\\s+").length < 80
                    || !validItems(section.keyConcepts(), 3) || !validItems(section.examples(), 1)) {
                throw new AiResponseValidationException("AI_GENERATION_FAILED: Section lacks a detailed explanation, concepts or examples.");
            }
            if (section.subtopics() == null || section.subtopics().size() < 2 || section.subtopics().size() > 6) {
                throw new AiResponseValidationException("AI_GENERATION_FAILED: Topic must include 2-6 detailed supporting subtopics.");
            }
            var titles = new HashSet<String>();
            for (var subtopic : section.subtopics()) {
                if (subtopic == null || subtopic.title() == null || subtopic.title().isBlank()
                        || subtopic.title().length() > 200
                        || !titles.add(subtopic.title().trim().toLowerCase(Locale.ROOT))
                        || subtopic.explanation() == null
                        || subtopic.explanation().trim().split("\\s+").length < 40
                        || !validItems(subtopic.keyConcepts(), 3) || !validItems(subtopic.examples(), 1)) {
                    throw new AiResponseValidationException("AI_GENERATION_FAILED: Supporting subtopics are incomplete or duplicated.");
                }
            }
            return section;
        } catch (JacksonException e) {
            throw new AiResponseValidationException("AI_GENERATION_FAILED: Malformed section response.");
        } catch (AiGenerationException e) {
            if (e instanceof AiResponseValidationException validation) throw validation;
            throw new AiResponseValidationException("AI_GENERATION_FAILED: Empty or incomplete section response.");
        }
    }

    private boolean validItems(List<String> items, int minimum) {
        return items != null && items.size() >= minimum
                && items.stream().allMatch(item -> item != null && !item.isBlank());
    }

    private String extractText(GeminiResponse response) {
        if (response == null || response.candidates == null || response.candidates.isEmpty()) {
            throw malformed();
        }
        var candidate = response.candidates.get(0);
        if (candidate == null || candidate.content == null || candidate.content.parts == null
                || candidate.content.parts.isEmpty()) {
            throw malformed();
        }
        if (candidate.finishReason != null && !"STOP".equals(candidate.finishReason)) {
            throw new AiGenerationException("AI_GENERATION_FAILED: Gemini did not complete the response.");
        }
        StringBuilder text = new StringBuilder();
        for (var part : candidate.content.parts) {
            if (part == null) {
                throw malformed();
            }
            if (!Boolean.TRUE.equals(part.thought) && part.text != null) {
                text.append(part.text);
            }
        }
        if (text.toString().isBlank()) {
            throw malformed();
        }
        return text.toString();
    }

    private AiGenerationException malformed() {
        return new AiGenerationException("AI_GENERATION_FAILED: Empty or malformed TOC response from Gemini API.");
    }
}
