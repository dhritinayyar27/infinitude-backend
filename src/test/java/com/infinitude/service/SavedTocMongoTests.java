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
