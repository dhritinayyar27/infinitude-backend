package com.infinitude.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    @Primary
    public RestTemplate restTemplate(
            @Value("${gemini.api.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${gemini.api.read-timeout-ms:45000}") int readTimeoutMs) {
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("Gemini connection and read timeouts must be positive.");
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    @Bean
    public RestTemplate notesRestTemplate(
            @Value("${gemini.api.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${gemini.api.notes-read-timeout-ms:120000}") int readTimeoutMs) {
        return restTemplate(connectTimeoutMs, readTimeoutMs);
    }
}
