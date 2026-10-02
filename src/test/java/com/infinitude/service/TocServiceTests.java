package com.infinitude.service;

import com.infinitude.ai.AiService;
import com.infinitude.ai.GeminiAiService;
import com.infinitude.ai.prompt.TocPromptBuilder;
import com.infinitude.ai.model.TocAiResponse;
import com.infinitude.ai.model.TocSection;
import com.infinitude.dto.TocSectionDto;
import com.infinitude.exception.AiGenerationException;
import com.infinitude.model.Note;
import com.infinitude.model.NotesStatus;
import com.infinitude.model.Section;
import com.infinitude.repository.NotesRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TocServiceTests {
    private final NotesRepository repository = mock(NotesRepository.class);
    private final AiService ai = mock(AiService.class);

    @Test
    void generatesTocWithServerKeyWithoutUserSettings() {
        Note note = draft();
        when(repository.findById("note")).thenReturn(Optional.of(note));
        when(repository.save(any(Note.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TocSection section = new TocSection();
        section.setTitle("Getting started");
        TocAiResponse response = new TocAiResponse();
        response.setSections(List.of(section));
        when(ai.generateTableOfContents("Java", "beginner", "server-key", "gemini-2.5-flash"))
            .thenReturn(response);

        Note result = new TocService(repository, ai, "server-key", "gemini-2.5-flash")
                .generateToc("note", "user");

        assertEquals(NotesStatus.TOC_READY, result.getStatus());
        assertEquals("Getting started", result.getSections().get(0).getTitle());
        assertNotNull(result.getSections().get(0).getSectionId());
        verify(ai).generateTableOfContents("Java", "beginner", "server-key", "gemini-2.5-flash");
    }

    @Test
    void missingServerKeyDoesNotMutateDraftOrCallAi() {
        Note note = draft();
        when(repository.findById("note")).thenReturn(Optional.of(note));

        assertThrows(IllegalStateException.class,
                () -> new TocService(repository, ai, "", "gemini-2.5-flash").generateToc("note", "user"));

        assertEquals(NotesStatus.DRAFT, note.getStatus());
        verify(repository, never()).save(any());
        verifyNoInteractions(ai);
    }

        @Test
        void aiFailureMarksTocAsFailed() {
        Note note = draft();
        when(repository.findById("note")).thenReturn(Optional.of(note));
        when(ai.generateTableOfContents(anyString(), anyString(), anyString(), anyString()))
            .thenThrow(new AiGenerationException("QUOTA_EXCEEDED"));

        assertThrows(AiGenerationException.class,
            () -> new TocService(repository, ai, "server-key", "gemini-2.5-flash").generateToc("note", "user"));

        assertEquals(NotesStatus.FAILED, note.getStatus());
        verify(repository, times(2)).save(note);
        }

        @Test
        void savingTocPreservesExistingSectionIds() {
        Note note = draft();
        Section section = new Section();
        section.setSectionId("existing-section");
        section.setTitle("Original title");
        note.setSections(List.of(section));
        when(repository.findById("note")).thenReturn(Optional.of(note));
        when(repository.save(any(Note.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Note result = new TocService(repository, ai, "server-key", "gemini-2.5-flash")
            .updateToc("note", "user", List.of(new TocSectionDto("existing-section", "Edited title", 1)));

        assertEquals("existing-section", result.getSections().get(0).getSectionId());
        assertEquals("Edited title", result.getSections().get(0).getTitle());
        assertEquals(NotesStatus.TOC_READY, result.getStatus());
        verifyNoInteractions(ai);
        }

        @Test
        void geminiUsesHeaderInsteadOfKeyInUrl() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("https://gemini.test/v1beta/models/gemini-2.5-flash:generateContent"))
            .andExpect(header("x-goog-api-key", "server-key"))
            .andRespond(withSuccess("""
                {"candidates":[{"content":{"parts":[{"text":"{\\\"sections\\\":[{\\\"title\\\":\\\"Basics\\\"}]}"}]}}]}
                """, MediaType.APPLICATION_JSON));

        TocAiResponse result = new GeminiAiService(restTemplate, "https://gemini.test", new ObjectMapper(),
            new TocPromptBuilder()).generateTableOfContents("Java", "BEGINNER", "server-key", "gemini-2.5-flash");

        assertEquals("Basics", result.getSections().get(0).getTitle());
        server.verify();
        }

        private Note draft() {
        Note note = new Note();
        note.setUserId("user");
        note.setTopic("Java");
        note.setDifficulty("beginner");
        note.setStatus(NotesStatus.DRAFT);
        return note;
    }
}