package com.infinitude.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CreateNotesRequest {

    @NotBlank
    @Size(max = 200)
    private String topic;

    @NotBlank
    @Pattern(regexp = "BEGINNER|INTERMEDIATE|ADVANCED",
             message = "must be BEGINNER, INTERMEDIATE, or ADVANCED")
    private String difficulty;

    @Pattern(regexp = "DETAILED|CONCISE|TECHNICAL|SIMPLE",
             message = "must be DETAILED, CONCISE, TECHNICAL, or SIMPLE")
    private String style = "DETAILED";

    public CreateNotesRequest() {
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = style;
    }
}
