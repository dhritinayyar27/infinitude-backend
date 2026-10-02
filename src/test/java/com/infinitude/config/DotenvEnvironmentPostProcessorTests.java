package com.infinitude.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DotenvEnvironmentPostProcessorTests {
    @TempDir
    Path directory;

    private final DotenvEnvironmentPostProcessor processor = new DotenvEnvironmentPostProcessor();

    @Test
    void loadsBackendEnvWhenLaunchedFromWorkspaceRoot() throws IOException {
        Path backend = Files.createDirectory(directory.resolve("infinitude-backend"));
        Files.writeString(directory.resolve(".env"), "MAIL_HOST=wrong-root.example.com\n");
        writeEnv(backend);
        StandardEnvironment environment = isolatedEnvironment();

        processor.load(environment, directory);

        assertEquals("smtp.example.com", environment.getProperty("MAIL_HOST"));
        assertEquals("fixture-key", environment.getProperty("GEMINI_API_KEY"));
    }

    @Test
    void loadsEnvFromBackendDirectoryAndResolvesApplicationProperties() throws IOException {
        writeEnv(directory);
        StandardEnvironment environment = isolatedEnvironment();
        environment.getPropertySources().addFirst(new PropertiesPropertySource("applicationProperties",
                PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"))));

        processor.load(environment, directory);

        assertEquals("smtp.example.com", environment.getProperty("spring.mail.host"));
        assertEquals("2525", environment.getProperty("spring.mail.port"));
        assertEquals("smtp-user", environment.getProperty("spring.mail.username"));
        assertEquals("password#with=punctuation", environment.getProperty("spring.mail.password"));
        assertEquals("sender@example.com", environment.getProperty("MAIL_FROM"));
        assertEquals("fixture-key", environment.getProperty("infinitude.gemini.api-key"));
        assertEquals("false", environment.getProperty("spring.mail.properties.mail.smtp.auth"));
    }

    @Test
    void higherPriorityEnvironmentAndJvmValuesOverrideDotenv() throws IOException {
        writeEnv(directory);
        StandardEnvironment environment = isolatedEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("osEnvironment",
                Map.of("MAIL_HOST", "os.example.com")));
        environment.getPropertySources().addFirst(new MapPropertySource("jvmProperties",
                Map.of("GEMINI_API_KEY", "jvm-key")));

        processor.load(environment, directory);

        assertEquals("os.example.com", environment.getProperty("MAIL_HOST"));
        assertEquals("jvm-key", environment.getProperty("GEMINI_API_KEY"));
        assertNull(System.getProperty("backendDotenv"));
    }

    @Test
    void absentEnvIsAllowedForOsConfiguredDeployments() {
        StandardEnvironment environment = isolatedEnvironment();

        assertDoesNotThrow(() -> processor.load(environment, directory));
        assertNull(environment.getPropertySources().get("backendDotenv"));
    }

    @Test
    void processorIsRegisteredAndRunsBeforeConfigData() throws IOException {
        assertEquals(DotenvEnvironmentPostProcessor.class.getName(),
                PropertiesLoaderUtils.loadProperties(new ClassPathResource("META-INF/spring.factories"))
                        .getProperty(EnvironmentPostProcessor.class.getName()));
        assertTrue(processor.getOrder() < ConfigDataEnvironmentPostProcessor.ORDER);
    }

    @Test
    void springStartupAutomaticallyLoadsAndResolvesDotenv() throws IOException {
        writeEnv(directory);
        String originalDirectory = System.getProperty("user.dir");
        SpringApplication application = new SpringApplication(ProbeConfiguration.class);
        application.setEnvironment(isolatedEnvironment());
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        try {
            System.setProperty("user.dir", directory.toString());
            try (ConfigurableApplicationContext context = application.run()) {
                assertEquals("smtp.example.com", context.getEnvironment().getProperty("spring.mail.host"));
                assertEquals("password#with=punctuation", context.getEnvironment().getProperty("spring.mail.password"));
                assertEquals("fixture-key", context.getEnvironment().getProperty("infinitude.gemini.api-key"));
            }
        } finally {
            System.setProperty("user.dir", originalDirectory);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeConfiguration {
    }

    private StandardEnvironment isolatedEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        return environment;
    }

    private void writeEnv(Path location) throws IOException {
        Files.writeString(location.resolve(".env"), """
                MAIL_HOST=smtp.example.com
                MAIL_PORT=2525
                MAIL_USERNAME=smtp-user
                MAIL_PASSWORD="password#with=punctuation"
                MAIL_FROM=sender@example.com
                MAIL_SMTP_AUTH=false
                GEMINI_API_KEY=fixture-key
                """);
    }
}