package com.infinitude.config;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        load(environment, Path.of(System.getProperty("user.dir")));
    }

    void load(ConfigurableEnvironment environment, Path workingDirectory) {
        Path backendDirectory = workingDirectory.resolve("infinitude-backend");
        Path directory = Files.isDirectory(backendDirectory) ? backendDirectory : workingDirectory;
        if (!Files.isRegularFile(directory.resolve(".env"))) {
            return;
        }

        Dotenv dotenv = Dotenv.configure().directory(directory.toString()).load();
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