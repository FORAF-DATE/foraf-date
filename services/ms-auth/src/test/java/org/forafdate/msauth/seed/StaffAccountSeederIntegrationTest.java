package org.forafdate.msauth.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.forafdate.msauth.users.AccountStatus;
import org.forafdate.msauth.users.Role;
import org.forafdate.msauth.users.UserEntity;
import org.forafdate.msauth.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@Transactional
class StaffAccountSeederIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private StaffAccountSeeder seeder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void createsOneActiveAccountPerStaffRole() {
        seeder.seed(credentials("Admin password 42", "Reception password 42", "Ophthalmologist password 42"));

        assertAccount("admin.test", "Ana Ruiz", Role.ADMINISTRATOR, "Admin password 42");
        assertAccount("receptionist.test", "Lucía Torres", Role.RECEPTIONIST, "Reception password 42");
        assertAccount("ophthalmologist.test", "Mateo Silva", Role.OPHTHALMOLOGIST, "Ophthalmologist password 42");
    }

    @Test
    void rerunKeepsSingleAccountPerUsernameAndRotatesPassword() {
        seeder.seed(credentials("Old admin password 42", "Old reception password 42", "Old ophthalmologist password 42"));
        UUID adminId = userRepository.findByUsername("admin.test").orElseThrow().getId();

        seeder.seed(credentials("New admin password 42", "New reception password 42", "New ophthalmologist password 42"));

        assertEquals(1, userRepository.countByUsername("admin.test"));
        assertEquals(1, userRepository.countByUsername("receptionist.test"));
        assertEquals(1, userRepository.countByUsername("ophthalmologist.test"));
        assertEquals(adminId, userRepository.findByUsername("admin.test").orElseThrow().getId());

        UserEntity admin = userRepository.findByUsername("admin.test").orElseThrow();
        assertFalse(passwordEncoder.matches("Old admin password 42", admin.getPasswordHash()));
        assertTrue(passwordEncoder.matches("New admin password 42", admin.getPasswordHash()));
    }

    @Test
    void rejectsBlankPasswordBeforeSavingAccounts() {
        StaffAccountSeeder.Credentials credentials = new StaffAccountSeeder.Credentials(
                " ",
                "Reception password 42",
                "Ophthalmologist password 42"
        );

        assertThrows(IllegalStateException.class, () -> seeder.seed(credentials));
        assertEquals(0, userRepository.count());
    }

    private void assertAccount(String username, String displayName, Role role, String password) {
        assertEquals(1, userRepository.countByUsername(username));
        UserEntity user = userRepository.findByUsername(username).orElseThrow();
        assertEquals(displayName, user.getDisplayName());
        assertEquals(role, user.getRole());
        assertEquals(AccountStatus.ACTIVE, user.getStatus());
        assertNotEquals(password, user.getPasswordHash());
        assertTrue(user.getPasswordHash().startsWith("$2"));
        assertTrue(passwordEncoder.matches(password, user.getPasswordHash()));
    }

    private StaffAccountSeeder.Credentials credentials(String admin, String receptionist, String ophthalmologist) {
        return new StaffAccountSeeder.Credentials(admin, receptionist, ophthalmologist);
    }
}
