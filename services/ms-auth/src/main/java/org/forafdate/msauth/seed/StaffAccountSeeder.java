package org.forafdate.msauth.seed;

import java.util.UUID;
import org.forafdate.msauth.users.AccountStatus;
import org.forafdate.msauth.users.Role;
import org.forafdate.msauth.users.UserEntity;
import org.forafdate.msauth.users.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class StaffAccountSeeder {

    private static final Logger LOGGER = LoggerFactory.getLogger(StaffAccountSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public StaffAccountSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public void seed(Credentials credentials) {
        requirePassword(credentials.adminPassword(), "AUTH_SEED_ADMIN_PASSWORD");
        requirePassword(credentials.receptionistPassword(), "AUTH_SEED_RECEPTIONIST_PASSWORD");
        requirePassword(credentials.ophthalmologistPassword(), "AUTH_SEED_OPHTHALMOLOGIST_PASSWORD");

        upsert("admin.test", "Ana Ruiz", Role.ADMINISTRATOR, credentials.adminPassword());
        upsert("receptionist.test", "Lucía Torres", Role.RECEPTIONIST, credentials.receptionistPassword());
        upsert("ophthalmologist.test", "Mateo Silva", Role.OPHTHALMOLOGIST, credentials.ophthalmologistPassword());

        LOGGER.info("Seeded 3 staff test accounts from environment variables");
    }

    private void upsert(String username, String displayName, Role role, String password) {
        UUID id = userRepository.findByUsername(username)
                .map(UserEntity::getId)
                .orElseGet(UUID::randomUUID);
        userRepository.saveAndFlush(new UserEntity(
                id,
                username,
                passwordEncoder.encode(password),
                displayName,
                role,
                AccountStatus.ACTIVE
        ));
    }

    private void requirePassword(String password, String variableName) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Missing required seed password: " + variableName);
        }
    }

    public record Credentials(
            String adminPassword,
            String receptionistPassword,
            String ophthalmologistPassword
    ) {
    }
}
