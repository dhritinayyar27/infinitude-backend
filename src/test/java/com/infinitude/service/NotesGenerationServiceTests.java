package com.infinitude.service;

import com.infinitude.ai.AiService;
import com.infinitude.ai.NotesTestContent;
import com.infinitude.ai.model.SectionAiResponse;
import com.infinitude.ai.prompt.SectionPromptBuilder;
import com.infinitude.config.GeminiConfiguration;
import com.infinitude.exception.AiGenerationException;
import com.infinitude.model.*;
import com.infinitude.repository.NotesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.infinitude.ai.prompt.DifficultyProfile;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotesGenerationServiceTests {
    private final NotesRepository repository = mock(NotesRepository.class);
    private final NotesService notes = mock(NotesService.class);
    private final NoteWorkflowStore workflow = mock(NoteWorkflowStore.class);
    private final AiService ai = mock(AiService.class);
    private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
    private final SectionPromptBuilder prompts = new SectionPromptBuilder(new ObjectMapper());
    private final NotesGenerationService service = new NotesGenerationService(notes, repository, workflow,
            ai, new GeminiConfiguration("key", "", "model"), prompts, executor);
    private Note note;

    @BeforeEach
    void setup() {
        note = new Note("user", "Java", "Java", "BEGINNER", "DETAILED");
        note.setId("note");
        note.setStatus(NotesStatus.TOC_READY);
        note.setTocSaved(true);
        note.setTocRevision(2);
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Section section = new Section();
            section.setSectionId("section-" + i);
            section.setTitle(List.of("Java Basics", "Variables", "Data Types").get(i));
            section.setLevel(i == 0 ? 1 : 2);
            section.setOrder(i + 1);
            sections.add(section);
        }
        note.setSections(sections);
        when(notes.getNote("note", "user")).thenReturn(note);
        when(workflow.replaceIdle(any(), anyLong())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void unsavedTocNeverCallsAiOrSchedulesWork() {
        note.setTocSaved(false);
        assertThrows(IllegalStateException.class, () -> service.generate("note", "user"));
        verifyNoInteractions(ai, workflow, executor);
    }

    @Test
    void savedTocSchedulesBackgroundWorkWithNoClientProvidedOutline() {
        Note result = service.generate("note", "user");
        assertEquals(NotesStatus.GENERATING_NOTES, result.getStatus());
        verify(workflow).replaceIdle(note, 2);
        verify(executor).execute(any(Runnable.class));
        verifyNoInteractions(ai);
    }

    @Test
    void competingGenerationDoesNotScheduleDuplicateAiCalls() {
        when(workflow.replaceIdle(any(), anyLong())).thenThrow(new IllegalStateException("Already running"));
        assertThrows(IllegalStateException.class, () -> service.generate("note", "user"));
        verifyNoInteractions(executor, ai);
    }

    @Test
    void queueRejectionIsVisibleAndRetryable() {
        doThrow(new TaskRejectedException("Busy")).when(executor).execute(any(Runnable.class));
        assertThrows(IllegalStateException.class, () -> service.generate("note", "user"));
        assertEquals(NotesStatus.FAILED, note.getStatus());
        verify(repository).save(note);
    }

    @Test
    void generatesEverySavedTopicAndBuildsExactHierarchy() {
        when(ai.generateSection(anyString(), eq("key"), eq("model"))).thenReturn(validResponse());
        service.generateSavedTopics(note);
        verify(ai, times(3)).generateSection(anyString(), eq("key"), eq("model"));
        assertEquals(NotesStatus.COMPLETED, note.getStatus());
        assertTrue(note.getSections().stream().allMatch(s -> s.getStatus() == SectionStatus.COMPLETED));
        String markdown = note.getMarkdownContent();
        assertTrue(markdown.startsWith("# 1 Java Basics\n"));
        assertTrue(markdown.contains("## 1.1 Variables\n"));
        assertTrue(markdown.contains("## 1.2 Data Types\n"));
        assertTrue(markdown.indexOf("1.1 Variables") < markdown.indexOf("1.2 Data Types"));
        assertEquals(2, note.getTocRevision());
        assertTrue(prompts.build(note, 1).contains("Variables"));
        assertTrue(prompts.build(note, 1).contains("Data Types"));
    }

    @Test
    void oneFailureDoesNotSkipOtherTopicsOrReportCompleteDocument() {
        when(ai.generateSection(anyString(), anyString(), anyString()))
                .thenReturn(validResponse())
                .thenThrow(new AiGenerationException("QUOTA_EXCEEDED"))
                .thenReturn(validResponse());
        service.generateSavedTopics(note);
        assertEquals(NotesStatus.FAILED, note.getStatus());
        assertNull(note.getMarkdownContent());
        assertEquals(SectionStatus.FAILED, note.getSections().get(1).getStatus());
        assertNotNull(note.getSections().get(1).getFailureReason());
        assertEquals(SectionStatus.COMPLETED, note.getSections().get(2).getStatus());
        verify(ai, times(3)).generateSection(anyString(), anyString(), anyString());
    }

    @Test
    void ownershipFailureNeverSchedulesWork() {
        when(notes.getNote("note", "user")).thenThrow(new com.infinitude.exception.NoteAccessDeniedException());
        assertThrows(com.infinitude.exception.NoteAccessDeniedException.class,
                () -> service.generate("note", "user"));
        verifyNoInteractions(workflow, executor, ai);
    }

    @Test
    void jobBoundaryPersistsInterruptedWorkAsFailed() {
        when(repository.save(any())).thenThrow(new IllegalStateException("Storage interruption")).thenReturn(note);
        doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; })
                .when(executor).execute(any(Runnable.class));
        service.generate("note", "user");
        assertEquals(NotesStatus.FAILED, note.getStatus());
        assertTrue(note.getSections().stream().allMatch(s -> s.getStatus() == SectionStatus.FAILED));
        assertNull(note.getMarkdownContent());
        verifyNoInteractions(ai);
    }

    @ParameterizedTest
    @EnumSource(DifficultyProfile.class)
    void rejectsNotesShorterThanSelectedLevelWithoutSkippingLaterTopics(DifficultyProfile profile) {
        note.setDifficulty(profile.name());
        int minimum = profile.minimumExplanationWords();
        var shortResponse = new SectionAiResponse("word ".repeat(minimum - 1),
                List.of("A", "B", "C"), List.of("Example"), NotesTestContent.subtopics());
        var adequateResponse = new SectionAiResponse("word ".repeat(minimum),
                List.of("A", "B", "C"), List.of("Example"), NotesTestContent.subtopics());
        when(ai.generateSection(anyString(), anyString(), anyString()))
                .thenReturn(shortResponse, shortResponse, adequateResponse, adequateResponse);
        service.generateSavedTopics(note);
        assertEquals(NotesStatus.FAILED, note.getStatus());
        assertEquals(SectionStatus.FAILED, note.getSections().getFirst().getStatus());
        assertEquals(SectionStatus.COMPLETED, note.getSections().get(1).getStatus());
        assertEquals(SectionStatus.COMPLETED, note.getSections().get(2).getStatus());
        assertNull(note.getMarkdownContent());
        verify(ai, times(4)).generateSection(anyString(), anyString(), anyString());
    }

    @Test
    void subsequentCallsUseSavedContentForContinuityAndKeepSelectedLevel() {
        note.setDifficulty("ADVANCED");
        var response = new SectionAiResponse("Shared shopping-cart terminology. " + "word ".repeat(300),
                List.of("A", "B", "C"), List.of("Shopping-cart example"), NotesTestContent.subtopics());
        when(ai.generateSection(anyString(), anyString(), anyString())).thenReturn(response);
        service.generateSavedTopics(note);
        ArgumentCaptor<String> captured = ArgumentCaptor.forClass(String.class);
        verify(ai, times(3)).generateSection(captured.capture(), anyString(), anyString());
        List<String> prompts = captured.getAllValues();
        assertFalse(prompts.get(0).contains("Shared shopping-cart terminology."));
        assertTrue(prompts.get(1).contains("Shared shopping-cart terminology."));
        assertTrue(prompts.get(2).contains("Shared shopping-cart terminology."));
        assertTrue(prompts.stream().allMatch(prompt -> prompt.contains("Difficulty: \"ADVANCED\"")));
        assertEquals(NotesStatus.COMPLETED, note.getStatus());
    }

    @Test
    void oneQualityRetryRepairsIncompleteOutputWithoutRegeneratingOtherTopics() {
        var incomplete = new SectionAiResponse("Short", List.of("A", "B", "C"), List.of("Example"));
        when(ai.generateSection(anyString(), anyString(), anyString()))
                .thenReturn(incomplete, validResponse(), validResponse(), validResponse());
        service.generateSavedTopics(note);
        assertEquals(NotesStatus.COMPLETED, note.getStatus());
        assertTrue(note.getSections().getFirst().getContent().contains("## Definitions"));
        ArgumentCaptor<String> captured = ArgumentCaptor.forClass(String.class);
        verify(ai, times(4)).generateSection(captured.capture(), anyString(), anyString());
        assertTrue(captured.getAllValues().get(1).contains("previous response failed completeness validation"));
        assertFalse(captured.getAllValues().get(2).contains("previous response failed completeness validation"));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "AI_GENERATION_TIMEOUT", "QUOTA_EXCEEDED", "AI_GENERATION_FAILED: HTTP 500"
    })
    void transportAndQuotaFailuresAreNotRetriedAsQualityFailures(String error) {
        when(ai.generateSection(anyString(), anyString(), anyString()))
                .thenThrow(new AiGenerationException(error)).thenReturn(validResponse(), validResponse());
        service.generateSavedTopics(note);
        verify(ai, times(3)).generateSection(anyString(), anyString(), anyString());
        assertEquals(SectionStatus.FAILED, note.getSections().getFirst().getStatus());
        assertEquals(SectionStatus.COMPLETED, note.getSections().getLast().getStatus());
    }

    private SectionAiResponse validResponse() {
        return new SectionAiResponse("Detailed explanation ".repeat(80),
                List.of("Concept A", "Concept B", "Concept C"), List.of("Worked example"), NotesTestContent.subtopics());
    }
}
