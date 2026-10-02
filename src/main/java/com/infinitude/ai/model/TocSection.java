package com.infinitude.ai.model;

import java.util.List;

public class TocSection {
    private String title;
    private List<String> subsections;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public List<String> getSubsections() {
        return subsections;
    }

    public void setSubsections(List<String> subsections) {
        this.subsections = subsections;
    }
}
