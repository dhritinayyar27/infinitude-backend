package com.infinitude.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class GeminiThinkingConfig {
    public String thinkingLevel;
    public Integer thinkingBudget;
}
