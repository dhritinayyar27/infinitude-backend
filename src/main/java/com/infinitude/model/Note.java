package com.infinitude.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "notes")
public class Note {

    @Id
    private String id;

    private String userId;
    private String title;
    private String topic;
    private String difficulty;
    private String style;
    private NotesStatus status = NotesStatus.DRAFT;
    private List<Section> sections = new ArrayList<>();
    private String markdownContent;
    private String markdownFilePath;
    private Instant createdAt;
    private Instant updatedAt;

    public Note() {
    }

    public Note(String userId, String topic, String title, String difficulty, String style) {
        this.userId = userId;
        this.topic = topic;
        this.title = title;
        this.difficulty = difficulty;
        this.style = style;
        this.status = NotesStatus.DRAFT;
        this.sections = new ArrayList<>();
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
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

    public NotesStatus getStatus() {
        return status;
    }

    public void setStatus(NotesStatus status) {
        this.status = status;
    }

    public List<Section> getSections() {
        return sections;
    }

    public void setSections(List<Section> sections) {
        this.sections = sections;
    }

    public String getMarkdownContent() {
        return markdownContent;
    }

    public void setMarkdownContent(String markdownContent) {
        this.markdownContent = markdownContent;
    }

    public String getMarkdownFilePath() {
        return markdownFilePath;
    }

    public void setMarkdownFilePath(String markdownFilePath) {
        this.markdownFilePath = markdownFilePath;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
