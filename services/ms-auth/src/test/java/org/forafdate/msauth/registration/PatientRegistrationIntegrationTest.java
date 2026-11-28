package org.forafdate.msauth.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.forafdate.msauth.users.AccountStatus;
import org.forafdate.msauth.users.Role;
import org.forafdate.msauth.users.UserEntity;
import org.forafdate.msauth.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class PatientRegistrationIntegrationTest {

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
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registersPatient() throws Exception {
        String password = "Example password 42";
        ResponseEntity<String> response = postRegistration("ana.vera", password, password);

        assertEquals(HttpStatus.CREATED.value(), response.getStatusCode().value());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("ana.vera", body.get("username").asText());

        UserEntity user = userRepository.findByUsername("ana.vera").orElseThrow();
        assertEquals(Role.PATIENT, user.getRole());
        assertEquals(AccountStatus.ACTIVE, user.getStatus());
        assertNotEquals(password, user.getPasswordHash());
        assertTrue(passwordEncoder.matches(password, user.getPasswordHash()));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsInvalidInput(String username, String password, String confirmation,
                             String expectedCode, String expectedField) throws Exception {
        ResponseEntity<String> response = postRegistration(username, password, confirmation);

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals(expectedCode, body.get("code").asText());
        assertEquals(expectedField, body.get("field").asText());
    }

    @Test
    void rejectsDuplicateUsername() throws Exception {
        String password = "Example password 42";
        assertEquals(
                HttpStatus.CREATED.value(),
                postRegistration("duplicate.user", password, password).getStatusCode().value()
        );

        ResponseEntity<String> response = postRegistration("duplicate.user", password, password);

        assertEquals(HttpStatus.CONFLICT.value(), response.getStatusCode().value());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("USERNAME_TAKEN", body.get("code").asText());
        assertEquals("username", body.get("field").asText());
        assertEquals(1L, userRepository.countByUsername("duplicate.user"));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/auth/patients/registration",
                new HttpEntity<>("{ not json", headers),
                String.class
        );

        assertEquals(HttpStatus.BAD_REQUEST.value(), response.getStatusCode().value());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals("MALFORMED_REQUEST", body.get("code").asText());
        assertFalse(body.has("field"));
    }

    @Test
    void createsSingleAccountForConcurrentRequests() throws Exception {
        int attempts = 8;
        String username = "concurrent.user";
        String password = "Example password 42";
        ExecutorService executor = Executors.newFixedThreadPool(attempts);
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < attempts; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return postRegistration(username, password, password);
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();

            int created = 0;
            int conflicts = 0;
            for (Future<ResponseEntity<String>> future : futures) {
                int status = future.get(20, TimeUnit.SECONDS).getStatusCode().value();
                if (status == HttpStatus.CREATED.value()) {
                    created++;
                } else if (status == HttpStatus.CONFLICT.value()) {
                    conflicts++;
                }
            }
            assertEquals(1, created);
            assertEquals(attempts - 1, conflicts);
            assertEquals(1L, userRepository.countByUsername(username));
        } finally {
            executor.shutdownNow();
        }
    }

    private ResponseEntity<String> postRegistration(String username, String password, String confirmation) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(
                "/api/auth/patients/registration",
                new HttpEntity<>(registrationJson(username, password, confirmation), headers),
                String.class
        );
    }

    private static String registrationJson(String username, String password, String confirmation) {
        Map<String, String> payload = new LinkedHashMap<>();
        if (username != null) {
            payload.put("username", username);
        }
        if (password != null) {
            payload.put("password", password);
        }
        if (confirmation != null) {
            payload.put("passwordConfirmation", confirmation);
        }
        try {
            return new ObjectMapper().writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Stream<Arguments> invalidRequests() {
        String password = "Example password 42";
        return Stream.of(
                Arguments.of(null, password, password, "REQUIRED", "username"),
                Arguments.of("ana", password, password, "USERNAME_LENGTH", "username"),
                Arguments.of("a".repeat(31), password, password, "USERNAME_LENGTH", "username"),
                Arguments.of("ana.vera", "short", "short", "PASSWORD_LENGTH", "password"),
                Arguments.of("ana.vera", "a".repeat(129), "a".repeat(129), "PASSWORD_LENGTH", "password"),
                Arguments.of("ana.vera", password, "Different password 42", "PASSWORD_MISMATCH", "passwordConfirmation"),
                Arguments.of("ana.vera", password, null, "REQUIRED", "passwordConfirmation")
        );
    }
}
