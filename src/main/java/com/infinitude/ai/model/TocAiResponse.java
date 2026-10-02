package com.infinitude.ai.model;

import java.util.List;

public class TocAiResponse {
    private String topic;
    private List<TocSection> sections;

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public List<TocSection> getSections() {
        return sections;
    }

    public void setSections(List<TocSection> sections) {
        this.sections = sections;
    }
}
