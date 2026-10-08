package com.infinitude.ai;

import com.infinitude.ai.model.SectionAiResponse;
import com.infinitude.ai.prompt.NotesGenerationGuide;
import com.infinitude.ai.prompt.TocPromptBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class NotesRequestTests {
    @Test
    void notesUseDedicatedClientActualGuideOutputBudgetAndProviderDefaultThinking() {
        ObjectMapper mapper = new ObjectMapper();
        RestTemplate toc = new RestTemplate();
        RestTemplate notes = new RestTemplate();
        MockRestServiceServer tocServer = MockRestServiceServer.bindTo(toc).build();
        MockRestServiceServer notesServer = MockRestServiceServer.bindTo(notes).build();
        var response = new SectionAiResponse("word ".repeat(160),
                List.of("A", "B", "C"), List.of("Use $x^2$."), NotesTestContent.subtopics());
        String envelope = mapper.writeValueAsString(Map.of("candidates", List.of(Map.of(
                "finishReason", "STOP", "content", Map.of("parts", List.of(Map.of(
                        "text", mapper.writeValueAsString(response))))))));
        notesServer.expect(requestTo("https://gemini.test/v1beta/models/gemini-2.5-flash:generateContent"))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(request -> {
                    var body = mapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request)
                            .getBodyAsString());
                    assertEquals(NotesGenerationGuide.systemPrompt(),
                            body.path("systemInstruction").path("parts").get(0).path("text").asText());
                    assertEquals("topic data", body.path("contents").get(0).path("parts").get(0).path("text").asText());
                    assertEquals(16384, body.path("generationConfig").path("maxOutputTokens").asInt());
                    var thinking = body.path("generationConfig").path("thinkingConfig");
                    assertTrue(thinking.isNull() || thinking.isMissingNode());
                }).andRespond(withSuccess(envelope, MediaType.APPLICATION_JSON));
        var ai = new GeminiAiService(toc, "https://gemini.test", mapper, new TocPromptBuilder(), notes);
        assertEquals(response, ai.generateSection("topic data", "test-key", "gemini-2.5-flash"));
        notesServer.verify();
        tocServer.verify();
    }
}
