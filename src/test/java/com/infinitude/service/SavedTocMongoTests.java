package com.infinitude.service;

import com.infinitude.ai.GeminiAiService;
import com.infinitude.ai.NotesTestContent;
import com.infinitude.ai.model.SectionAiResponse;
import com.infinitude.ai.prompt.SectionPromptBuilder;
import com.infinitude.ai.prompt.TocPromptBuilder;
import com.infinitude.config.GeminiConfiguration;
import com.infinitude.dto.TocSectionDto;
import com.infinitude.mapper.NotesMapper;
import com.infinitude.model.*;
import com.infinitude.repository.NotesRepository;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@EnabledIfSystemProperty(named = "notes.mongo-test", matches = "true")
class SavedTocMongoTests {
    private MongoClient client;
    private String database;
    private NotesRepository repository;
    private NoteWorkflowStore workflow;
    private NotesService notes;

    @BeforeEach
    void setup() {
        database = "infinitude_feature_test_" + UUID.randomUUID().toString().replace("-", "");
        client = MongoClients.create("mongodb://localhost:27017/?serverSelectionTimeoutMS=3000");
        MongoTemplate mongo = new MongoTemplate(client, database);
        repository = new MongoRepositoryFactory(mongo).getRepository(NotesRepository.class);
        workflow = new NoteWorkflowStore(mongo);
        notes = new NotesService(repository, workflow);
    }

    @AfterEach
    void cleanup() {
        if (client != null) {
            try {
                client.getDatabase(database).drop();
            } finally {
                client.close();
            }
        }
    }

    @Test
    void savedOutlineToRestGenerationToPersistedReaderAndUpdatedOutline() {
        ObjectMapper mapper = new ObjectMapper();
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        GeminiAiService ai = new GeminiAiService(rest, "https://gemini.test", mapper, new TocPromptBuilder());
        GeminiConfiguration config = new GeminiConfiguration("test-key", "", "gemini-2.5-flash");
        TocService toc = new TocService(repository, ai, config, workflow);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; })
                .when(executor).execute(any(Runnable.class));
        NotesGenerationService generation = new NotesGenerationService(notes, repository, workflow,
                ai, config, new SectionPromptBuilder(mapper), executor);
        Note draft = notes.createNote("test-user", "Java", "BEGINNER", "DETAILED");
        assertThrows(IllegalStateException.class, () -> generation.generate(draft.getId(), "test-user"));

        expect(server, mapper, java.util.Map.of("sections",
                List.of(java.util.Map.of("title", "Java Basics", "subsections", List.of("Variables", "Data Types")))));
        for (int i = 0; i < 3; i++) {
            expect(server, mapper, new SectionAiResponse("Detailed explanation ".repeat(80),
                    List.of("Concept one", "Concept two", "Concept three"), List.of("Worked example"), NotesTestContent.subtopics()));
        }
        Note review = toc.generateToc(draft.getId(), "test-user");
        assertFalse(review.isTocSaved());
        assertEquals(List.of(1, 2, 2), review.getSections().stream().map(Section::getLevel).toList());
        assertThrows(IllegalStateException.class, () -> generation.generate(draft.getId(), "test-user"));
        var payload = review.getSections().stream().map(section -> {
            TocSectionDto dto = new TocSectionDto(section.getSectionId(), section.getTitle(), section.getOrder());
            dto.setLevel(section.getLevel());
            return dto;
        }).toList();
        Note saved = toc.updateToc(draft.getId(), "test-user", payload);
        long revision = saved.getTocRevision();
        Note complete = generation.generate(draft.getId(), "test-user");
        assertEquals(NotesStatus.COMPLETED, complete.getStatus());
        assertTrue(complete.getMarkdownContent().contains("## 1.1 Variables"));
        assertTrue(complete.getMarkdownContent().contains("## 1.2 Data Types"));
        assertTrue(complete.getSections().stream().allMatch(s -> s.getStatus() == SectionStatus.COMPLETED));
        assertTrue(complete.getSections().stream().allMatch(s -> s.getContent().contains("Definitions")
                && s.getContent().contains("Practical applications")));
        String responseJson = mapper.writeValueAsString(new NotesMapper().toResponse(complete));
        assertTrue(responseJson.contains("\"tocSaved\":true"));
        assertTrue(responseJson.contains("\"level\":2"));

        TocSectionDto edited = new TocSectionDto(payload.get(1).getSectionId(), "Updated Variables", 2);
        edited.setLevel(2);
        Note updated = toc.updateToc(draft.getId(), "test-user", List.of(payload.get(0), edited, payload.get(2)));
        assertEquals(revision + 1, updated.getTocRevision());
        assertNull(updated.getMarkdownContent());
        assertTrue(updated.getSections().stream().allMatch(s -> s.getContent() == null));
        assertEquals(NotesStatus.TOC_READY, updated.getStatus());
        server.verify();
        server.reset();
        for (int i = 0; i < 3; i++) {
            expect(server, mapper, new SectionAiResponse("Updated detailed explanation ".repeat(80),
                    List.of("Updated one", "Updated two", "Updated three"), List.of("Updated example"), NotesTestContent.subtopics()));
        }
        Note regenerated = generation.generate(draft.getId(), "test-user");
        assertEquals(NotesStatus.COMPLETED, regenerated.getStatus());
        assertTrue(regenerated.getMarkdownContent().contains("## 1.1 Updated Variables\n"));
        assertFalse(regenerated.getMarkdownContent().contains("## 1.1 Variables\n"));
        assertTrue(regenerated.getSections().stream().allMatch(s -> s.getContent().startsWith("Updated")));
        server.verify();
    }

    @Test
    void atomicClaimAllowsOnlyOneWorkerAndBlocksWritesAndDeletion() throws Exception {
        Note draft = notes.createNote("test-user", "Java", "BEGINNER", "DETAILED");
        Note first = notes.getNote(draft.getId(), "test-user");
        Note second = notes.getNote(draft.getId(), "test-user");
        first.setStatus(NotesStatus.GENERATING_NOTES);
        second.setStatus(NotesStatus.GENERATING_NOTES);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = pool.invokeAll(List.of(
                    () -> claim(first), () -> claim(second)));
            assertEquals(1, results.stream().filter(result -> {
                try { return result.get(); } catch (Exception ex) { throw new AssertionError(ex); }
            }).count());
        }
        assertThrows(IllegalStateException.class, () -> workflow.replaceIdle(first, 0));
        assertThrows(IllegalStateException.class, () -> notes.deleteNote(draft.getId(), "test-user"));
        assertTrue(repository.existsById(draft.getId()));
    }

    @Test
    void failedTopicRegenerationUpdatesOnlyThatTopicThroughRestAndMongo() {
        ObjectMapper mapper = new ObjectMapper();
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        GeminiAiService ai = new GeminiAiService(rest, "https://gemini.test", mapper, new TocPromptBuilder());
        GeminiConfiguration config = new GeminiConfiguration("test-key", "", "gemini-2.5-flash");
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(call -> { call.getArgument(0, Runnable.class).run(); return null; })
                .when(executor).execute(any(Runnable.class));
        NotesGenerationService generation = new NotesGenerationService(notes, repository, workflow,
                ai, config, new SectionPromptBuilder(mapper), executor);
        Note note = partiallyFailedNote();

        server.expect(requestTo("https://gemini.test/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withSuccess("{\"candidates\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://gemini.test/v1beta/models/gemini-2.5-flash:generateContent"))
                .andRespond(withSuccess("{\"candidates\":[]}", MediaType.APPLICATION_JSON));
        generation.regenerateSection(note.getId(), "s1", "test-user");
        Note stillFailed = notes.getNote(note.getId(), "test-user");
        assertEquals(NotesStatus.FAILED, stillFailed.getStatus());
        assertEquals(SectionStatus.FAILED, stillFailed.getSections().get(1).getStatus());
        assertNotNull(stillFailed.getSections().get(1).getFailureReason());
        assertEquals("Kept zero", stillFailed.getSections().get(0).getContent());
        server.verify();
        server.reset();

        expect(server, mapper, new SectionAiResponse("Detailed explanation ".repeat(80),
                List.of("Concept one", "Concept two", "Concept three"), List.of("Worked example"),
                NotesTestContent.subtopics()));
        generation.regenerateSection(note.getId(), "s1", "test-user");
        Note complete = notes.getNote(note.getId(), "test-user");
        assertEquals(NotesStatus.COMPLETED, complete.getStatus());
        assertEquals("Kept zero", complete.getSections().get(0).getContent());
        assertEquals("Kept two", complete.getSections().get(2).getContent());
        assertEquals(SectionStatus.COMPLETED, complete.getSections().get(1).getStatus());
        assertTrue(complete.getSections().get(1).getContent().contains("Definitions"));
        assertTrue(complete.getMarkdownContent().contains("## 1.1 Variables"));
        assertThrows(IllegalStateException.class,
                () -> generation.regenerateSection(note.getId(), "s1", "test-user"));
        server.verify();
    }

    @Test
    void concurrentSectionClaimsSucceedOnceAndNeverTouchOtherSections() throws Exception {
        Note note = partiallyFailedNote();
        Note first = notes.getNote(note.getId(), "test-user");
        Note second = notes.getNote(note.getId(), "test-user");
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = pool.invokeAll(List.of(
                    () -> claimSection(first), () -> claimSection(second)));
            assertEquals(1, results.stream().filter(result -> {
                try { return result.get(); } catch (Exception ex) { throw new AssertionError(ex); }
            }).count());
        }
        Note claimed = notes.getNote(note.getId(), "test-user");
        assertEquals(NotesStatus.GENERATING_NOTES, claimed.getStatus());
        assertEquals(SectionStatus.GENERATING, claimed.getSections().get(1).getStatus());
        assertEquals("Kept zero", claimed.getSections().get(0).getContent());
        assertEquals(SectionStatus.COMPLETED, claimed.getSections().get(2).getStatus());
        assertThrows(IllegalStateException.class, () -> notes.deleteNote(note.getId(), "test-user"));

        workflow.recoverInterrupted();
        Note recovered = notes.getNote(note.getId(), "test-user");
        assertEquals(NotesStatus.FAILED, recovered.getStatus());
        assertEquals(SectionStatus.FAILED, recovered.getSections().get(1).getStatus());
        assertNotNull(recovered.getSections().get(1).getFailureReason());
        assertEquals("Kept two", recovered.getSections().get(2).getContent());
    }

    @Test
    void multiTopicClaimMarksOnlyFailedTargetsAndRejectsCompletedIds() {
        Note note = partiallyFailedNote();
        Note stored = repository.findById(note.getId()).orElseThrow();
        stored.getSections().get(2).setStatus(SectionStatus.FAILED);
        stored.getSections().get(2).setContent(null);
        repository.save(stored);

        Note fresh = notes.getNote(note.getId(), "test-user");
        assertThrows(IllegalStateException.class,
                () -> workflow.claimFailedSections(fresh, List.of("s1", "s0")));
        Note unchanged = notes.getNote(note.getId(), "test-user");
        assertEquals(NotesStatus.FAILED, unchanged.getStatus());
        assertEquals(SectionStatus.FAILED, unchanged.getSections().get(1).getStatus());

        workflow.claimFailedSections(unchanged, List.of("s1", "s2"));
        Note claimed = notes.getNote(note.getId(), "test-user");
        assertEquals(NotesStatus.GENERATING_NOTES, claimed.getStatus());
        assertEquals(SectionStatus.COMPLETED, claimed.getSections().get(0).getStatus());
        assertEquals("Kept zero", claimed.getSections().get(0).getContent());
        assertEquals(SectionStatus.GENERATING, claimed.getSections().get(1).getStatus());
        assertEquals(SectionStatus.GENERATING, claimed.getSections().get(2).getStatus());
        assertNull(claimed.getSections().get(1).getFailureReason());
        assertThrows(IllegalStateException.class,
                () -> workflow.claimFailedSections(claimed, List.of("s1", "s2")));
    }

    private Note partiallyFailedNote() {
        Note note = notes.createNote("test-user", "Java", "BEGINNER", "DETAILED");
        note.setTocSaved(true);
        note.setTocRevision(1);
        note.setStatus(NotesStatus.FAILED);
        List<Section> sections = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Section section = new Section();
            section.setSectionId("s" + i);
            section.setTitle(List.of("Java Basics", "Variables", "Data Types").get(i));
            section.setLevel(i == 0 ? 1 : 2);
            section.setOrder(i + 1);
            section.setStatus(i == 1 ? SectionStatus.FAILED : SectionStatus.COMPLETED);
            section.setContent(i == 1 ? null : List.of("Kept zero", "", "Kept two").get(i));
            section.setFailureReason(i == 1 ? "Earlier failure" : null);
            sections.add(section);
        }
        note.setSections(sections);
        return repository.save(note);
    }

    private boolean claimSection(Note note) {
        try {
            workflow.claimFailedSections(note, List.of("s1"));
            return true;
        } catch (IllegalStateException ex) {
            return false;
        }
    }

    private boolean claim(Note note) {
        try {
            workflow.replaceIdle(note, 0);
            return true;
        } catch (IllegalStateException ex) {
            return false;
        }
    }

    private void expect(MockRestServiceServer server, ObjectMapper mapper, Object content) {
        var part = java.util.Map.of("text", mapper.writeValueAsString(content));
        var candidate = java.util.Map.of("finishReason", "STOP",
                "content", java.util.Map.of("parts", List.of(part)));
        String body = mapper.writeValueAsString(java.util.Map.of("candidates", List.of(candidate)));
        server.expect(requestTo("https://gemini.test/v1beta/models/gemini-2.5-flash:generateContent"))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}
