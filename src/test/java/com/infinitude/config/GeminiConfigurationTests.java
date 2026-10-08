package com.infinitude.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GeminiConfigurationTests {
    @Test
    void commaPoolTrimsDropsBlanksAndDeduplicatesInFirstSeenOrder() {
        GeminiKeyPool pool = GeminiKeyPool.parse(" first, , second ,first,,THIRD, second ");

        assertEquals(List.of("first", "second", "THIRD"), pool.keys());
        assertEquals("first,second,THIRD", pool.commaSeparatedKeys());
        assertThrows(UnsupportedOperationException.class, () -> pool.keys().add("fourth"));
    }

    @Test
    void singularLegacyAndEmptyPoolsAreSupported() {
        assertEquals(List.of("legacy"), GeminiKeyPool.resolve(" legacy ", null).keys());
        assertEquals(List.of("legacy"), GeminiKeyPool.resolve("legacy", " , , ").keys());
        assertTrue(GeminiKeyPool.resolve(null, null).isEmpty());
        assertTrue(GeminiKeyPool.parse(" , ").isEmpty());
    }

    @Test
    void pluralPoolReplacesRatherThanCombinesWithLegacy() {
        GeminiConfiguration configuration = new GeminiConfiguration("legacy", " new,other,new ", null);

        assertEquals(List.of("new", "other"), configuration.keyPool().keys());
        assertEquals("gemini-3.5-flash-lite", configuration.preferredModel());
        assertEquals("GeminiKeyPool[REDACTED]", configuration.keyPool().toString());
        assertEquals("GeminiConfiguration[REDACTED]", configuration.toString());
    }

    @Test
    void fallbackModelsHaveExactRequiredOrderAndAreImmutable() {
        List<String> expected = List.of("gemini-3.8-flash", "gemini-3.6-flash",
                "gemini-3.5-flash-lite", "gemini-3.7-flash", "gemini-3.5-flash",
                "gemini-flash-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite",
                "gemini-3.1-flash-lite-preview", "gemini-flash-lite-latest");

        assertEquals(expected, GeminiConfiguration.FALLBACK_MODELS);
        List<String> defaultCandidates = List.of("gemini-3.5-flash-lite", "gemini-3.8-flash",
                "gemini-3.6-flash", "gemini-3.7-flash", "gemini-3.5-flash",
                "gemini-flash-latest", "gemini-2.5-flash", "gemini-2.5-flash-lite",
                "gemini-3.1-flash-lite-preview", "gemini-flash-lite-latest");
        assertEquals(defaultCandidates, GeminiConfiguration.modelCandidates(null));
        assertEquals(defaultCandidates, GeminiConfiguration.modelCandidates(" "));
        assertThrows(UnsupportedOperationException.class,
                () -> GeminiConfiguration.FALLBACK_MODELS.add("other"));
    }

    @Test
    void preferredModelIsFirstWithoutRepeatingItDuringFallback() {
        List<String> candidates = GeminiConfiguration.modelCandidates(" gemini-2.5-flash ");

        assertEquals("gemini-2.5-flash", candidates.getFirst());
        assertEquals(10, candidates.size());
        assertEquals(1, candidates.stream().filter("gemini-2.5-flash"::equals).count());
        assertEquals("gemini-3.8-flash", candidates.get(1));
        assertEquals("custom", GeminiConfiguration.modelCandidates(" custom ").getFirst());
        assertEquals(11, GeminiConfiguration.modelCandidates("custom").size());
        assertThrows(UnsupportedOperationException.class, () -> candidates.add("other"));
    }

    @Test
    void springConfigurationResolvesOsAndJvmOverridesAheadOfDotenv() throws IOException {
        StandardEnvironment environment = isolatedEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("backendDotenv",
                Map.of("GEMINI_API_KEY", "dotenv-legacy", "GEMINI_API_KEYS", "dotenv-plural",
                        "GEMINI_MODEL", "dotenv-model")));
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("osEnvironment",
                Map.of("GEMINI_API_KEYS", " os-first,os-second,os-first ", "GEMINI_MODEL", "os-model")));
        environment.getPropertySources().addFirst(new MapPropertySource("jvmProperties",
                Map.of("GEMINI_API_KEYS", " jvm-first,jvm-second ", "GEMINI_MODEL", " jvm-model ")));

        try (AnnotationConfigApplicationContext context = context(environment)) {
            GeminiConfiguration configuration = context.getBean(GeminiConfiguration.class);
            assertEquals(List.of("jvm-first", "jvm-second"), configuration.keyPool().keys());
            assertEquals("jvm-model", configuration.preferredModel());
        }

        environment.getPropertySources().remove("jvmProperties");
        try (AnnotationConfigApplicationContext context = context(environment)) {
            GeminiConfiguration configuration = context.getBean(GeminiConfiguration.class);
            assertEquals(List.of("os-first", "os-second"), configuration.keyPool().keys());
            assertEquals("os-model", configuration.preferredModel());
        }
    }

    @Test
    void applicationPropertiesResolveLegacyListAndDefaultModel() throws IOException {
        StandardEnvironment environment = isolatedEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("fixtureEnvironment",
                Map.of("GEMINI_API_KEY", " first,second,first ")));

        try (AnnotationConfigApplicationContext context = context(environment)) {
            GeminiConfiguration configuration = context.getBean(GeminiConfiguration.class);
            assertEquals(List.of("first", "second"), configuration.keyPool().keys());
            assertEquals("gemini-3.5-flash-lite", configuration.preferredModel());
        }
    }

    private AnnotationConfigApplicationContext context(StandardEnvironment environment) throws IOException {
        environment.getPropertySources().addLast(new PropertiesPropertySource("applicationProperties",
                PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"))));
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setEnvironment(environment);
        context.register(GeminiConfiguration.class);
        context.refresh();
        return context;
    }

    private StandardEnvironment isolatedEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        return environment;
    }
}
