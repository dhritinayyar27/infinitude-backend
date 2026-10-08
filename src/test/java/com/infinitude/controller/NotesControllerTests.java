package com.infinitude.controller;

import com.infinitude.exception.GlobalExceptionHandler;
import com.infinitude.mapper.NotesMapper;
import com.infinitude.model.Note;
import com.infinitude.model.NotesStatus;
import com.infinitude.security.AuthenticatedUser;
import com.infinitude.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;

import java.security.Principal;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NotesControllerTests {
    private final NotesService notes = mock(NotesService.class);
    private final TocService toc = mock(TocService.class);
    private final NotesGenerationService generation = mock(NotesGenerationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new NotesController(notes, toc, new NotesMapper(), generation))
            .setMessageConverters(new JacksonJsonHttpMessageConverter())
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    private final Principal principal = UsernamePasswordAuthenticationToken.authenticated(
            new AuthenticatedUser("user", "test@example.test"), null, List.of());

    @Test
    void generationAcceptsSavedNoteIdAndReturns202() throws Exception {
        Note note = new Note("user", "Java", "Java", "BEGINNER", "DETAILED");
        note.setId("note");
        note.setTocSaved(true);
        note.setStatus(NotesStatus.GENERATING_NOTES);
        when(generation.generate("note", "user")).thenReturn(note);
        mvc.perform(post("/api/notes/note/generate").principal(principal))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.tocSaved").value(true))
                .andExpect(jsonPath("$.status").value("GENERATING_NOTES"));
        verify(generation).generate("note", "user");
    }

    @Test
    void unsavedTocProducesExplicitConflict() throws Exception {
        when(generation.generate("note", "user")).thenThrow(new IllegalStateException("Save the TOC first."));
        mvc.perform(post("/api/notes/note/generate").principal(principal))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATE"));
    }

    @Test
    void nestedBlankTitlesAndInvalidLevelsAreRejected() throws Exception {
        mvc.perform(put("/api/notes/note/toc").principal(principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sections\":[{\"title\":\" \",\"level\":6}]}"))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertInstanceOf(
                        org.springframework.web.bind.MethodArgumentNotValidException.class,
                        result.getResolvedException(), String.valueOf(result.getResolvedException())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
        verifyNoInteractions(toc);
    }

    @Test
    void nullTopicsAndEmptyTocAreRejected() throws Exception {
        for (String body : List.of("{\"sections\":[null]}", "{\"sections\":[]}")) {
            mvc.perform(put("/api/notes/note/toc").principal(principal)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(toc);
    }

    @Test
    void legacyFlatOutlineDefaultsToTopLevelAndServerAssignsOrder() throws Exception {
        Note note = new Note("user", "Java", "Java", "BEGINNER", "DETAILED");
        when(toc.updateToc(eq("note"), eq("user"), anyList())).thenReturn(note);
        mvc.perform(put("/api/notes/note/toc").principal(principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sections\":[{\"title\":\"Basics\"}]}"))
                .andExpect(status().isOk());
        verify(toc).updateToc(eq("note"), eq("user"),
                argThat(topics -> topics.size() == 1 && topics.get(0).getLevel() == 1));
    }
}
