package com.infinitude.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerPortConfigurationTests {
    @Test
    void renderPortIsUsedWhenLocalPortIsNotSet() throws IOException {
        assertEquals("10000", resolvePort(Map.of("PORT", "10000")));
    }

    @Test
    void explicitSpringEnvironmentOverrideRemainsSupportedOutsideDocker() throws IOException {
        assertEquals("8081", resolvePort(Map.of("PORT", "10000", "SERVER_PORT", "8081")));
    }

    @Test
    void localPortRemainsSupported() throws IOException {
        assertEquals("8081", resolvePort(Map.of("SERVER_PORT", "8081")));
    }

    @Test
    void defaultPortRemains8080() throws IOException {
        assertEquals("8080", resolvePort(Map.of()));
    }

    private String resolvePort(Map<String, Object> variables) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("testEnvironment", variables));
        environment.getPropertySources().addLast(new PropertiesPropertySource("applicationProperties",
                PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"))));
        return environment.getProperty("server.port");
    }
}
