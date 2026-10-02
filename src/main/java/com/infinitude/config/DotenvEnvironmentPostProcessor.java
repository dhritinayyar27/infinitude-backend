package com.infinitude.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvException;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        load(environment, Path.of(System.getProperty("user.dir")));
    }

    void load(ConfigurableEnvironment environment, Path workingDirectory) {
        Path directory = List.of(workingDirectory.resolve("Backend"),
                        workingDirectory.resolve("infinitude-backend"), workingDirectory).stream()
                .filter(candidate -> Files.isRegularFile(candidate.resolve(".env")))
                .findFirst().orElse(null);
        if (directory == null) {
            return;
        }

        Dotenv dotenv;
        try {
            dotenv = Dotenv.configure().directory(directory.toString()).load();
        } catch (DotenvException e) {
            // Parser exception messages/causes can contain full secret-bearing lines.
            throw new IllegalStateException("Unable to load backend environment configuration.");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        dotenv.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE)
                .forEach(entry -> values.put(entry.getKey(), entry.getValue()));
        environment.getPropertySources().addLast(new MapPropertySource("backendDotenv", values));
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER - 1;
    }
}