package com.infinitude.mapper;

import com.infinitude.dto.NotesResponse;
import com.infinitude.dto.SectionResponse;
import com.infinitude.model.Note;
import com.infinitude.model.Section;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class NotesMapper {

    public NotesResponse toResponse(Note note) {
        NotesResponse response = new NotesResponse();
        response.setId(note.getId());
        response.setUserId(note.getUserId());
        response.setTitle(note.getTitle());
        response.setTopic(note.getTopic());
        response.setDifficulty(note.getDifficulty());
        response.setStyle(note.getStyle());
        response.setTocSaved(note.isTocSaved());
        response.setTocRevision(note.getTocRevision());
        response.setStatus(note.getStatus() != null ? note.getStatus().name() : null);
        response.setSections(note.getSections() != null
                ? note.getSections().stream()
                        .map(this::toSectionResponse)
                        .collect(Collectors.toList())
                : List.of());
        response.setMarkdownContent(note.getMarkdownContent());
        response.setCreatedAt(note.getCreatedAt());
        response.setUpdatedAt(note.getUpdatedAt());
        return response;
    }

    public SectionResponse toSectionResponse(Section section) {
        SectionResponse response = new SectionResponse(
                section.getSectionId(),
                section.getTitle(),
                section.getOrder(),
                section.getContent(),
                section.getStatus() != null ? section.getStatus().name() : null,
                section.getFailureReason());
        response.setLevel(section.getLevel());
        return response;
    }

    public List<NotesResponse> toResponseList(List<Note> notes) {
        return notes.stream().map(this::toResponse).collect(Collectors.toList());
    }
}
