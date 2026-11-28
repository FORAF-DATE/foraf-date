package org.forafdate.msauth.users;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    boolean existsByUsername(String username);

    Optional<UserEntity> findByUsername(String username);

    long countByUsername(String username);
}
