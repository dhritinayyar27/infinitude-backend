package com.infinitude.repository;

import com.infinitude.model.Note;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface NotesRepository extends MongoRepository<Note, String> {
    List<Note> findByUserIdOrderByCreatedAtDesc(String userId);
    long countByUserId(String userId);
}
