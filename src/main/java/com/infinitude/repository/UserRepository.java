package com.infinitude.repository;

import com.infinitude.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * Spring Data MongoDB repository for {@link User} (§10, §17.2 PROJECT_ARCHITECTURE.md).
 * No business logic here - lookups only.
 */
public interface UserRepository extends MongoRepository<User, String> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
