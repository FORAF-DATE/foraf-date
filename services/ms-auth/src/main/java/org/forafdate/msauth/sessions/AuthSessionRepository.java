package org.forafdate.msauth.sessions;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthSessionRepository extends JpaRepository<AuthSessionEntity, UUID> {
    @EntityGraph(attributePaths = "user")
    Optional<AuthSessionEntity> findByTokenHash(String tokenHash);
}
