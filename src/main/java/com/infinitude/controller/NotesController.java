package com.infinitude.controller;

import com.infinitude.dto.CreateNotesRequest;
import com.infinitude.dto.NotesResponse;
import com.infinitude.dto.UpdateTocRequest;
import com.infinitude.mapper.NotesMapper;
import com.infinitude.model.Note;
import com.infinitude.security.AuthenticatedUser;
import com.infinitude.service.NotesService;
import com.infinitude.service.TocService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/notes")
public class NotesController {

    private final NotesService notesService;
    private final TocService tocService;
    private final NotesMapper notesMapper;

    public NotesController(NotesService notesService,
                           TocService tocService,
                           NotesMapper notesMapper) {
        this.notesService = notesService;
        this.tocService = tocService;
        this.notesMapper = notesMapper;
    }

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    @PostMapping
    public ResponseEntity<NotesResponse> createNote(@Valid @RequestBody CreateNotesRequest request,
                                                     Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        Note note = notesService.createNote(user.getUserId(), request.getTopic(),
                request.getDifficulty(), request.getStyle());
        return ResponseEntity.status(HttpStatus.CREATED).body(notesMapper.toResponse(note));
    }

    @GetMapping
    public ResponseEntity<List<NotesResponse>> listNotes(Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        List<Note> notes = notesService.listNotes(user.getUserId());
        return ResponseEntity.ok(notesMapper.toResponseList(notes));
    }

    @GetMapping("/{noteId}")
    public ResponseEntity<NotesResponse> getNote(@PathVariable String noteId,
                                                  Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        Note note = notesService.getNote(noteId, user.getUserId());
        return ResponseEntity.ok(notesMapper.toResponse(note));
    }

    @DeleteMapping("/{noteId}")
    public ResponseEntity<Void> deleteNote(@PathVariable String noteId,
                                            Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        notesService.deleteNote(noteId, user.getUserId());
        return ResponseEntity.noContent().build();
    }

    // -------------------------------------------------------------------------
    // TOC
    // -------------------------------------------------------------------------

    @PostMapping("/{noteId}/toc/generate")
    public ResponseEntity<NotesResponse> generateToc(@PathVariable String noteId,
                                                      Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        Note note = tocService.generateToc(noteId, user.getUserId());
        return ResponseEntity.ok(notesMapper.toResponse(note));
    }

    @GetMapping("/{noteId}/toc")
    public ResponseEntity<NotesResponse> getToc(@PathVariable String noteId,
                                                 Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        Note note = tocService.getToc(noteId, user.getUserId());
        return ResponseEntity.ok(notesMapper.toResponse(note));
    }

    @PutMapping("/{noteId}/toc")
    public ResponseEntity<NotesResponse> updateToc(@PathVariable String noteId,
                                                    @Valid @RequestBody UpdateTocRequest request,
                                                    Authentication authentication) {
        AuthenticatedUser user = (AuthenticatedUser) authentication.getPrincipal();
        Note note = tocService.updateToc(noteId, user.getUserId(), request.getSections());
        return ResponseEntity.ok(notesMapper.toResponse(note));
    }

}
