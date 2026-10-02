package com.infinitude.config;

import com.infinitude.ai.AiService;
import com.infinitude.ai.GeminiAiService;
import com.infinitude.ai.prompt.TocPromptBuilder;
import com.infinitude.exception.AiGenerationException;
import com.infinitude.model.Note;
import com.infinitude.model.NotesStatus;
import com.infinitude.model.Section;
import com.infinitude.model.SectionStatus;
import com.infinitude.repository.NotesRepository;
import com.infinitude.service.TocService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Offline integration: real dotenv/config/service/AI/parser beans; only the
 * repository and HTTP transport are doubles. No Boot auto-configuration,
 * component scan, actual workspace dotenv, Mongo client, or network connection.
 */
class GeminiPoolIntegrationTests {
    private static final String BASE = "http://gemini.invalid";
    private static final String FIRST = "fake-integration-first";
    private static final String SECOND = "fake-integration-second";
    private static final String PREFERRED = "fixture-preferred-model";
    private static final String NEXT_MODEL = "gemini-3.8-flash";
    private static final String PROVIDER_PRIVATE = "fixture-private-provider-body";
    private static final String SUCCESS = """
            {"candidates":[{"content":{"parts":[{"text":"{\\\"sections\\\":[{\\\"title\\\":\\\"Java foundations\\\"},{\\\"title\\\":\\\"Practical examples\\\"}]}"}]}}]}
            """;

    @TempDir
    Path workspace;

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "plural-only", "plural-overrides-legacy"})
    void workspaceDotenvWiresRealBeansAndMixedFailoverPersistsRetrievableToc(String mode)
            throws IOException {
        try (Fixture fixture = start(mode)) {
            GeminiConfiguration config = fixture.context.getBean(GeminiConfiguration.class);
            assertEquals(List.of(FIRST, SECOND), config.keyPool().keys());
            assertEquals(PREFERRED, config.preferredModel());
            assertInstanceOf(GeminiAiService.class, fixture.context.getBean(AiService.class));
            assertEquals("GeminiConfiguration[REDACTED]", config.toString());
            assertEquals("GeminiKeyPool[REDACTED]", config.keyPool().toString());
            if (mode.equals("plural-only")) {
                assertNull(fixture.context.getEnvironment().getProperty("GEMINI_API_KEY"));
            }

            fixture.failure(PREFERRED, FIRST, HttpStatus.FORBIDDEN);
            fixture.failure(PREFERRED, SECOND, HttpStatus.SERVICE_UNAVAILABLE);
            fixture.success(NEXT_MODEL, SECOND);

            Note result = fixture.toc().generateToc("note", "owner");
            assertEquals(NotesStatus.TOC_READY, result.getStatus());
            assertEquals(List.of(NotesStatus.GENERATING_TOC, NotesStatus.TOC_READY),
                    fixture.saved.stream().map(Note::getStatus).toList());
            assertTrue(fixture.saved.get(0).getSections().isEmpty());
            assertNotNull(fixture.saved.get(1).getUpdatedAt());
            verify(fixture.repository, times(2)).save(any(Note.class));

            Note retrieved = fixture.toc().getToc("note", "owner");
            assertNotSame(result, retrieved, "Retrieval must use the saved repository snapshot");
            assertEquals(NotesStatus.TOC_READY, retrieved.getStatus());
            assertEquals(List.of("Java foundations", "Practical examples"),
                    retrieved.getSections().stream().map(Section::getTitle).toList());
            assertEquals(List.of(1, 2), retrieved.getSections().stream().map(Section::getOrder).toList());
            assertEquals(2, retrieved.getSections().stream().map(Section::getSectionId).distinct().count());
            retrieved.getSections().forEach(section -> {
                assertTrue(org.bson.types.ObjectId.isValid(section.getSectionId()));
                assertEquals(SectionStatus.PENDING, section.getStatus());
            });
            assertRedacted(fixture.mapper.writeValueAsString(retrieved));
            assertEquals(List.of(PREFERRED + "|" + FIRST, PREFERRED + "|" + SECOND,
                    NEXT_MODEL + "|" + SECOND), fixture.attempts);
        }
    }

    @Test
    void allKeysRejectedPersistsFailedAndDoesNotTryOtherModels() throws IOException {
        try (Fixture fixture = start("plural-only")) {
            fixture.failure(PREFERRED, FIRST, HttpStatus.FORBIDDEN);
            fixture.failure(PREFERRED, SECOND, HttpStatus.FORBIDDEN);

            AiGenerationException error = assertThrows(AiGenerationException.class,
                    () -> fixture.toc().generateToc("note", "owner"));
            assertEquals("INVALID_API_KEY", error.getMessage());
            assertSafeException(error);
            assertEquals(List.of(NotesStatus.GENERATING_TOC, NotesStatus.FAILED),
                    fixture.saved.stream().map(Note::getStatus).toList());
            Note retrieved = fixture.toc().getToc("note", "owner");
            assertEquals(NotesStatus.FAILED, retrieved.getStatus());
            assertTrue(retrieved.getSections().isEmpty());
            assertNotNull(retrieved.getUpdatedAt());
            verify(fixture.repository, times(2)).save(any(Note.class));
            assertEquals(List.of(PREFERRED + "|" + FIRST, PREFERRED + "|" + SECOND), fixture.attempts);
            assertRedacted(fixture.mapper.writeValueAsString(retrieved));
        }
    }

    @Test
    void terminalProviderErrorIsRedactedAcrossRealAiAndTocLayers() throws IOException {
        try (Fixture fixture = start("plural-only")) {
            fixture.failure(PREFERRED, FIRST, HttpStatus.INTERNAL_SERVER_ERROR);

            AiGenerationException error = assertThrows(AiGenerationException.class,
                    () -> fixture.toc().generateToc("note", "owner"));
            assertEquals("AI_GENERATION_FAILED: HTTP 500", error.getMessage());
            assertSafeException(error);
            assertEquals(List.of(NotesStatus.GENERATING_TOC, NotesStatus.FAILED),
                    fixture.saved.stream().map(Note::getStatus).toList());
            assertEquals(NotesStatus.FAILED, fixture.toc().getToc("note", "owner").getStatus());
            assertEquals(List.of(PREFERRED + "|" + FIRST), fixture.attempts);
        }
    }

    @Test
    void malformedWorkspaceBackendFixtureFailsWithoutLeakingParserLine() throws IOException {
        Path backend = Files.createDirectory(workspace.resolve("Backend"));
        Files.writeString(backend.resolve(".env"), "invalid line " + FIRST + " " + PROVIDER_PRIVATE);
        StandardEnvironment environment = isolatedEnvironment();

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new DotenvEnvironmentPostProcessor().load(environment, workspace));
        assertEquals("Unable to load backend environment configuration.", error.getMessage());
        assertSafeException(error);
        assertNull(environment.getPropertySources().get("backendDotenv"));
    }

    private Fixture start(String mode) throws IOException {
        Path backend = Files.createDirectory(workspace.resolve("Backend"));
        // Decoy proves workspace-root lookup selects Backend rather than root.
        Files.writeString(workspace.resolve(".env"), "GEMINI_API_KEY=fake-wrong-root\n");
        String pool = " " + FIRST + ", ," + SECOND + "," + FIRST + " ";
        String keys = switch (mode) {
            case "legacy" -> "GEMINI_API_KEY=\"" + pool + "\"\n";
            case "plural-only" -> "GEMINI_API_KEYS=\"" + pool + "\"\n";
            case "plural-overrides-legacy" ->
                    "GEMINI_API_KEY=fake-ignored-legacy\nGEMINI_API_KEYS=\"" + pool + "\"\n";
            default -> throw new IllegalArgumentException(mode);
        };
        Files.writeString(backend.resolve(".env"), keys + "GEMINI_MODEL=" + PREFERRED + "\n");
        StandardEnvironment environment = isolatedEnvironment();
        environment.getPropertySources().addFirst(new PropertiesPropertySource("applicationProperties",
                PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"))));
        environment.getPropertySources().addFirst(new MapPropertySource("offlineTransport",
                Map.of("gemini.api.base-url", BASE)));
        new DotenvEnvironmentPostProcessor().load(environment, workspace);
        return new Fixture(environment);
    }

    private static StandardEnvironment isolatedEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        return environment;
    }

    private static void assertSafeException(Throwable error) {
        assertNull(error.getCause());
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        assertRedacted(trace.toString());
    }

    private static void assertRedacted(String text) {
        for (String secret : List.of(FIRST, SECOND, PROVIDER_PRIVATE,
                "fake-ignored-legacy", "fake-wrong-root")) {
            assertFalse(text.contains(secret), "Private fixture data escaped its server-only boundary");
        }
    }

    private static Note snapshot(Note source) {
        Note copy = new Note();
        copy.setId(source.getId());
        copy.setUserId(source.getUserId());
        copy.setTopic(source.getTopic());
        copy.setDifficulty(source.getDifficulty());
        copy.setStatus(source.getStatus());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setSections(new ArrayList<>(source.getSections()));
        return copy;
    }

    private static final class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final NotesRepository repository = mock(NotesRepository.class);
        final ObjectMapper mapper = new ObjectMapper();
        final RestTemplate template = new RestTemplate();
        // Installed before bean creation: an unexpected HTTP call fails instead of reaching the network.
        final MockRestServiceServer server = MockRestServiceServer.bindTo(template)
                .ignoreExpectOrder(false).build();
        final List<Note> saved = new ArrayList<>();
        final List<String> attempts = new ArrayList<>();

        Fixture(StandardEnvironment environment) {
            Note draft = new Note();
            draft.setId("note");
            draft.setUserId("owner");
            draft.setTopic("Java");
            draft.setDifficulty("beginner");
            AtomicReference<Note> stored = new AtomicReference<>(draft);
            when(repository.findById("note")).thenAnswer(invocation ->
                    Optional.of(snapshot(stored.get())));
            when(repository.save(any(Note.class))).thenAnswer(invocation -> {
                Note note = invocation.getArgument(0);
                Note copy = snapshot(note);
                saved.add(copy);
                stored.set(copy);
                return note;
            });
            context.setEnvironment(environment);
            context.registerBean(RestTemplate.class, () -> template);
            context.registerBean(ObjectMapper.class, () -> mapper);
            context.registerBean(NotesRepository.class, () -> repository);
            context.register(GeminiConfiguration.class, GeminiAiService.class,
                    TocService.class, TocPromptBuilder.class);
            try {
                context.refresh();
            } catch (RuntimeException | Error error) {
                context.close();
                throw error;
            }
        }

        TocService toc() {
            return context.getBean(TocService.class);
        }

        void failure(String model, String key, HttpStatus status) {
            expect(model, key).andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":{\"message\":\"" + PROVIDER_PRIVATE + " " + FIRST + " " + SECOND + "\"}}"));
        }

        void success(String model, String key) {
            expect(model, key).andRespond(withSuccess(SUCCESS, MediaType.APPLICATION_JSON));
        }

        org.springframework.test.web.client.ResponseActions expect(String model, String key) {
            return server.expect(requestTo(BASE + "/v1beta/models/" + model + ":generateContent"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(header("x-goog-api-key", key))
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(content().string(containsString("Java")))
                    .andExpect(content().string(containsString("beginner")))
                    .andExpect(request -> {
                        assertEquals(List.of(key), request.getHeaders().get("x-goog-api-key"));
                        assertFalse(key.contains(","));
                        assertNull(request.getURI().getQuery());
                        assertRedacted(request.getURI().toString());
                        attempts.add(model + "|" + key);
                    });
        }

        @Override
        public void close() {
            try {
                server.verify();
            } finally {
                context.close();
            }
        }
    }
}
