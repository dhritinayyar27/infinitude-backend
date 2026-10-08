package com.infinitude.ai;

import com.infinitude.ai.model.*;
import com.infinitude.ai.parser.GeminiResponseParser;
import com.infinitude.exception.AiGenerationException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SectionResponseParserTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final GeminiResponseParser parser = new GeminiResponseParser(mapper);

    private GeminiResponse envelope(Object body) {
        GeminiPart part = new GeminiPart();
        part.text = mapper.writeValueAsString(body);
        GeminiContent content = new GeminiContent();
        content.parts = List.of(part);
        GeminiCandidate candidate = new GeminiCandidate();
        candidate.content = content;
        candidate.finishReason = "STOP";
        GeminiResponse response = new GeminiResponse();
        response.candidates = List.of(candidate);
        return response;
    }

    @Test
    void acceptsMeaningfulStructuredContent() {
        var section = new SectionAiResponse("word ".repeat(80),
                List.of("One", "Two", "Three"), List.of("Worked example"), NotesTestContent.subtopics());
        assertEquals(section, parser.parseSection(envelope(section)));
    }

    @Test
    void rejectsSummaryOnlyMissingExamplesAndBlankConcepts() {
        assertThrows(AiGenerationException.class, () -> parser.parseSection(envelope(
                new SectionAiResponse("Short summary", List.of("One", "Two", "Three"), List.of("Example")))));
        assertThrows(AiGenerationException.class, () -> parser.parseSection(envelope(
                new SectionAiResponse("word ".repeat(80), List.of("One", "Two", "Three"), List.of()))));
        assertThrows(AiGenerationException.class, () -> parser.parseSection(envelope(
                new SectionAiResponse("word ".repeat(80), List.of("One", "", "Three"), List.of("Example")))));
    }

    @Test
    void rejectsTruncatedResponse() {
        GeminiResponse response = envelope(new SectionAiResponse("word ".repeat(80),
                List.of("One", "Two", "Three"), List.of("Example")));
        response.candidates.get(0).finishReason = "MAX_TOKENS";
        assertThrows(AiGenerationException.class, () -> parser.parseSection(response));
    }

    @Test
    void rejectsMissingShallowAndDuplicateSupportingSubtopics() {
        var subtopics = NotesTestContent.subtopics();
        assertThrows(AiGenerationException.class, () -> parser.parseSection(envelope(
                new SectionAiResponse("word ".repeat(80), List.of("A", "B", "C"), List.of("Example")))));
        assertThrows(AiGenerationException.class, () -> parser.parseSection(envelope(
                new SectionAiResponse("word ".repeat(80), List.of("A", "B", "C"), List.of("Example"),
                        List.of(subtopics.getFirst(), subtopics.getFirst())))));
        var shallow = new SubtopicAiResponse("Shallow", "Summary", List.of("A", "B", "C"), List.of("Example"));
        assertThrows(AiGenerationException.class, () -> parser.parseSection(envelope(
                new SectionAiResponse("word ".repeat(80), List.of("A", "B", "C"), List.of("Example"),
                        List.of(shallow, subtopics.getFirst())))));
    }

    @Test
    void preservesMarkdownAndLatexAfterJsonDecoding() {
        String example = "Use $x^2$.\n\n$$\n\\frac{a}{b}\n$$\n\n```java\nint x = 2;\n```\n\n| A | B |\n| --- | --- |\n| 1 | 2 |";
        var response = new SectionAiResponse("word ".repeat(80),
                List.of("**One**", "Two", "Three"), List.of(example), NotesTestContent.subtopics());
        assertEquals(example, parser.parseSection(envelope(response)).examples().getFirst());
    }

    @Test
    void providerSafetyRejectionIsTerminalRatherThanAQualityRetry() {
        var response = envelope(new SectionAiResponse("word ".repeat(80),
                List.of("A", "B", "C"), List.of("Example"), NotesTestContent.subtopics()));
        response.candidates.getFirst().finishReason = "SAFETY";
        var error = assertThrows(AiGenerationException.class, () -> parser.parseSection(response));
        assertFalse(error instanceof com.infinitude.exception.AiResponseValidationException);
        assertTrue(error.getMessage().startsWith("AI_GENERATION_BLOCKED"));
    }
}
