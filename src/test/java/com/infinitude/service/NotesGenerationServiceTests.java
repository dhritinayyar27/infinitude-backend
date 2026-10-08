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
import java.util.Set;

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
    private final List<Long> sleeps = new ArrayList<>();

    @BeforeEach
    void setup() {
        service.setSleeper(sleeps::add);
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
                .thenThrow(new AiGenerationException("AI_GENERATION_FAILED: HTTP 400"))
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
            "AI_GENERATION_TIMEOUT", "QUOTA_EXCEEDED", "AI_GENERATION_FAILED: HTTP 500",
            "AI_GENERATION_FAILED: Unable to read or reach Gemini API."
    })
    void transientFailuresAreRetriedWithBackoffAndRecover(String error) {
        when(ai.generateSection(anyString(), anyString(), anyString()))
                .thenThrow(new AiGenerationException(error)).thenReturn(validResponse());
        service.generateSavedTopics(note);
        verify(ai, times(4)).generateSection(anyString(), anyString(), anyString());
        assertEquals(1, sleeps.size());
        assertTrue(sleeps.getFirst() > 0);
        assertTrue(note.getSections().stream().allMatch(s -> s.getStatus() == SectionStatus.COMPLETED));
        assertEquals(NotesStatus.COMPLETED, note.getStatus());
    }

    @Test
    void persistentServerErrorGivesUpAfterBoundedRetries() {
        when(ai.generateSection(anyString(), anyString(), anyString()))
                .thenThrow(new AiGenerationException("AI_GENERATION_FAILED: HTTP 503"))
                .thenThrow(new AiGenerationException("AI_GENERATION_FAILED: HTTP 503"))
                .thenThrow(new AiGenerationException("AI_GENERATION_FAILED: HTTP 503"))
                .thenReturn(validResponse());
        service.generateSavedTopics(note);
        verify(ai, times(5)).generateSection(anyString(), anyString(), anyString());
        assertEquals(List.of(2000L, 4000L), sleeps);
        assertEquals(SectionStatus.FAILED, note.getSections().getFirst().getStatus());
        assertEquals(SectionStatus.COMPLETED, note.getSections().getLast().getStatus());
        assertEquals(NotesStatus.FAILED, note.getStatus());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "AI_GENERATION_BLOCKED", "AI_GENERATION_FAILED: HTTP 400"
    })
    void permanentFailuresAreNeverRetried(String error) {
        when(ai.generateSection(anyString(), anyString(), anyString()))
                .thenThrow(new AiGenerationException(error)).thenReturn(validResponse());
        service.generateSavedTopics(note);
        verify(ai, times(3)).generateSection(anyString(), anyString(), anyString());
        assertTrue(sleeps.isEmpty());
        assertEquals(SectionStatus.FAILED, note.getSections().getFirst().getStatus());
    }

    @Test
    void bulkRegenerationOfTenTopicsCallsAiOnlyForTheThreeFailedOnes() {
        tenTopicsWithThreeFailures();
        runInline();
        List<String> before = note.getSections().stream().map(Section::getContent).toList();
        List<Integer> expectedOrder = new ArrayList<>(List.of(1, 4, 8));
        when(ai.generateSection(anyString(), anyString(), anyString())).thenAnswer(call -> {
            // Same prompt builder and live note context as full generation, in TOC order.
            assertEquals(prompts.build(note, expectedOrder.removeFirst()), call.getArgument(0));
            return validResponse();
        });

        service.regenerateFailedSections("note", List.of("topic-2", "topic-5", "topic-9"), "user");

        verify(ai, times(3)).generateSection(anyString(), eq("key"), eq("model"));
        assertTrue(expectedOrder.isEmpty());
        verify(workflow).claimFailedSections(note, Set.of("topic-2", "topic-5", "topic-9"));
        verify(workflow, never()).replaceIdle(any(), anyLong());
        for (int i = 0; i < 10; i++) {
            if (i != 1 && i != 4 && i != 8) {
                assertEquals(before.get(i), note.getSections().get(i).getContent(), "topic " + (i + 1) + " untouched");
            }
        }
        assertTrue(note.getSections().stream().allMatch(s -> s.getStatus() == SectionStatus.COMPLETED));
        assertEquals(NotesStatus.COMPLETED, note.getStatus());
    }

    @Test
    void generateOnFailedNoteWithTenTopicsRunsOnlyTheThreeFailedOnes() {
        tenTopicsWithThreeFailures();
        runInline();
        when(ai.generateSection(anyString(), anyString(), anyString())).thenReturn(validResponse());
        service.generate("note", "user");
        verify(ai, times(3)).generateSection(anyString(), anyString(), anyString());
        verify(workflow, never()).replaceIdle(any(), anyLong());
        assertEquals("Original content 1", note.getSections().get(0).getContent());
        assertEquals("Original content 10", note.getSections().get(9).getContent());
        assertEquals(NotesStatus.COMPLETED, note.getStatus());
    }

    @Test
    void bulkRegenerationKeepsStillFailingTopicsRetryable() {
        tenTopicsWithThreeFailures();
        runInline();
        when(ai.generateSection(anyString(), anyString(), anyString()))
                .thenReturn(validResponse())
                .thenThrow(new AiGenerationException("AI_GENERATION_FAILED: HTTP 400"))
                .thenReturn(validResponse());
        service.regenerateFailedSections("note", List.of("topic-2", "topic-5", "topic-9"), "user");
        assertEquals(SectionStatus.COMPLETED, note.getSections().get(1).getStatus());
        assertEquals(SectionStatus.FAILED, note.getSections().get(4).getStatus());
        assertNotNull(note.getSections().get(4).getFailureReason());
        assertEquals(SectionStatus.COMPLETED, note.getSections().get(8).getStatus());
        assertEquals(NotesStatus.FAILED, note.getStatus());
    }

    @Test
    void bulkRegenerationRejectsCompletedTopicsAndEmptyRequests() {
        tenTopicsWithThreeFailures();
        assertThrows(IllegalStateException.class,
                () -> service.regenerateFailedSections("note", List.of("topic-2", "topic-1"), "user"));
        assertThrows(IllegalArgumentException.class,
                () -> service.regenerateFailedSections("note", List.of(), "user"));
        assertThrows(com.infinitude.exception.SectionNotFoundException.class,
                () -> service.regenerateFailedSections("note", List.of("missing"), "user"));
        verify(workflow, never()).claimFailedSections(any(), anyCollection());
        verifyNoInteractions(ai, executor);
    }

    @Test
    void generateOnFailedNoteWithoutFailedTopicsIsRejected() {
        note.setStatus(NotesStatus.FAILED);
        note.getSections().forEach(s -> { s.setStatus(SectionStatus.COMPLETED); s.setContent("Done"); });
        assertThrows(IllegalStateException.class, () -> service.generate("note", "user"));
        verifyNoInteractions(ai, executor);
        verify(workflow, never()).replaceIdle(any(), anyLong());
    }

    @Test
    void regeneratingFailedTopicPreservesCompletedTopicsAndCompletesNote() {
        markPartiallyFailed();
        runInline();
        when(ai.generateSection(anyString(), eq("key"), eq("model"))).thenReturn(validResponse());
        service.regenerateSection("note", "section-1", "user");
        verify(workflow).claimFailedSections(note, Set.of("section-1"));
        verify(ai, times(1)).generateSection(anyString(), anyString(), anyString());
        assertEquals("Kept zero", note.getSections().get(0).getContent());
        assertEquals("Kept two", note.getSections().get(2).getContent());
        assertEquals(SectionStatus.COMPLETED, note.getSections().get(1).getStatus());
        assertNull(note.getSections().get(1).getFailureReason());
        assertTrue(note.getSections().get(1).getContent().contains("## Definitions"));
        assertEquals(NotesStatus.COMPLETED, note.getStatus());
        assertTrue(note.getMarkdownContent().contains("Kept zero"));
        assertTrue(note.getMarkdownContent().contains("## 1.1 Variables"));
    }

    @Test
    void regenerationUsesSameContextAsOriginalGeneration() {
        markPartiallyFailed();
        runInline();
        String expectedPrompt = prompts.build(note, 1);
        when(ai.generateSection(anyString(), anyString(), anyString())).thenReturn(validResponse());
        service.regenerateSection("note", "section-1", "user");
        verify(ai).generateSection(expectedPrompt, "key", "model");
    }

    @Test
    void failedRegenerationKeepsTopicRetryableWithSpecificReason() {
        markPartiallyFailed();
        runInline();
        when(ai.generateSection(anyString(), anyString(), anyString()))
                .thenThrow(new AiGenerationException("AI_GENERATION_TIMEOUT: slow"));
        service.regenerateSection("note", "section-1", "user");
        verify(ai, times(2)).generateSection(anyString(), anyString(), anyString());
        assertEquals(List.of(2000L), sleeps);
        assertEquals(SectionStatus.FAILED, note.getSections().get(1).getStatus());
        assertNull(note.getSections().get(1).getContent());
        assertTrue(note.getSections().get(1).getFailureReason().contains("too long"));
        assertEquals("Kept zero", note.getSections().get(0).getContent());
        assertEquals(NotesStatus.FAILED, note.getStatus());
        assertNull(note.getMarkdownContent());
    }

    @Test
    void invalidRegeneratedContentIsRetriedOnceThenMarkedFailed() {
        markPartiallyFailed();
        runInline();
        var incomplete = new SectionAiResponse("Short", List.of("A", "B", "C"), List.of("Example"));
        when(ai.generateSection(anyString(), anyString(), anyString())).thenReturn(incomplete);
        service.regenerateSection("note", "section-1", "user");
        verify(ai, times(2)).generateSection(anyString(), anyString(), anyString());
        assertEquals(SectionStatus.FAILED, note.getSections().get(1).getStatus());
        assertTrue(note.getSections().get(1).getFailureReason().contains("incomplete or invalid"));
        assertEquals(NotesStatus.FAILED, note.getStatus());
    }

    @Test
    void onlyFailedTopicsCanBeRegenerated() {
        markPartiallyFailed();
        assertThrows(IllegalStateException.class, () -> service.regenerateSection("note", "section-0", "user"));
        assertThrows(com.infinitude.exception.SectionNotFoundException.class,
                () -> service.regenerateSection("note", "missing", "user"));
        note.setStatus(NotesStatus.GENERATING_NOTES);
        assertThrows(IllegalStateException.class, () -> service.regenerateSection("note", "section-1", "user"));
        verify(workflow, never()).claimFailedSections(any(), anyCollection());
        verifyNoInteractions(executor, ai);
    }

    @Test
    void duplicateRegenerationClaimNeverSchedulesWork() {
        markPartiallyFailed();
        doThrow(new IllegalStateException("Already claimed")).when(workflow).claimFailedSections(any(), anyCollection());
        assertThrows(IllegalStateException.class, () -> service.regenerateSection("note", "section-1", "user"));
        verifyNoInteractions(executor, ai);
    }

    @Test
    void busyQueueReturnsRegeneratingTopicToFailed() {
        markPartiallyFailed();
        doThrow(new TaskRejectedException("Busy")).when(executor).execute(any(Runnable.class));
        assertThrows(IllegalStateException.class, () -> service.regenerateSection("note", "section-1", "user"));
        assertEquals(SectionStatus.FAILED, note.getSections().get(1).getStatus());
        assertNotNull(note.getSections().get(1).getFailureReason());
        assertEquals(NotesStatus.FAILED, note.getStatus());
        assertEquals("Kept zero", note.getSections().get(0).getContent());
        verify(repository).save(note);
    }

    @Test
    void retryingFailedNoteRegeneratesOnlyFailedTopics() {
        markPartiallyFailed();
        runInline();
        when(ai.generateSection(anyString(), anyString(), anyString())).thenReturn(validResponse());
        service.generate("note", "user");
        verify(ai, times(1)).generateSection(anyString(), anyString(), anyString());
        assertEquals("Kept zero", note.getSections().get(0).getContent());
        assertEquals("Kept two", note.getSections().get(2).getContent());
        assertEquals(NotesStatus.COMPLETED, note.getStatus());
    }

    @Test
    void rejectedCredentialsStopFurtherGuaranteedFailingCalls() {
        when(ai.generateSection(anyString(), anyString(), anyString()))
                .thenThrow(new AiGenerationException("INVALID_API_KEY"));
        service.generateSavedTopics(note);
        verify(ai, times(1)).generateSection(anyString(), anyString(), anyString());
        assertTrue(note.getSections().stream().allMatch(s -> s.getStatus() == SectionStatus.FAILED
                && s.getFailureReason().contains("credentials")));
        assertEquals(NotesStatus.FAILED, note.getStatus());
    }

    private void markPartiallyFailed() {
        note.setStatus(NotesStatus.FAILED);
        for (int i = 0; i < 3; i++) {
            Section section = note.getSections().get(i);
            section.setStatus(i == 1 ? SectionStatus.FAILED : SectionStatus.COMPLETED);
            section.setContent(i == 1 ? null : List.of("Kept zero", "", "Kept two").get(i));
            section.setFailureReason(i == 1 ? "Earlier failure" : null);
        }
        stubClaim();
    }

    private void stubClaim() {
        doAnswer(call -> {
            Note claimed = call.getArgument(0);
            java.util.Collection<String> ids = call.getArgument(1);
            claimed.setStatus(NotesStatus.GENERATING_NOTES);
            claimed.setMarkdownContent(null);
            for (Section section : claimed.getSections()) {
                if (ids.contains(section.getSectionId())) {
                    assertEquals(SectionStatus.FAILED, section.getStatus(), "only FAILED topics may be claimed");
                    section.setStatus(SectionStatus.GENERATING);
                    section.setFailureReason(null);
                }
            }
            return claimed;
        }).when(workflow).claimFailedSections(any(), anyCollection());
    }

    /** 10 topics: 2, 5 and 9 (1-based) failed, the rest completed with distinct content. */
    private void tenTopicsWithThreeFailures() {
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Section section = new Section();
            section.setSectionId("topic-" + (i + 1));
            section.setTitle("Topic " + (i + 1));
            section.setLevel(1);
            section.setOrder(i + 1);
            boolean failed = i == 1 || i == 4 || i == 8;
            section.setStatus(failed ? SectionStatus.FAILED : SectionStatus.COMPLETED);
            section.setContent(failed ? null : "Original content " + (i + 1));
            section.setFailureReason(failed ? "Generation failed" : null);
            sections.add(section);
        }
        note.setSections(sections);
        note.setStatus(NotesStatus.FAILED);
        stubClaim();
    }


    private void runInline() {
        doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; })
                .when(executor).execute(any(Runnable.class));
    }

    private SectionAiResponse validResponse() {
        return new SectionAiResponse("Detailed explanation ".repeat(80),
                List.of("Concept A", "Concept B", "Concept C"), List.of("Worked example"), NotesTestContent.subtopics());
    }
}
