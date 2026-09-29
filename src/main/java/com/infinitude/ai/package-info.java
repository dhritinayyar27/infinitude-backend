/**
 * Gemini AI integration, owned by the AI Agent.
 *
 * <p><b>ABSOLUTE RULE:</b> Gemini REST API only ({@code POST
 * https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent} over plain
 * HTTP). Never add a Gemini/Google GenAI Java SDK dependency (e.g. {@code com.google.genai.*})
 * unless {@code PROJECT_ARCHITECTURE.md} has been explicitly amended and approved.</p>
 *
 * <p>Business code depends only on {@code AiService}; {@code GeminiRestClient} is the sole place
 * that constructs HTTP requests to Gemini.</p>
 */
package com.infinitude.ai;
