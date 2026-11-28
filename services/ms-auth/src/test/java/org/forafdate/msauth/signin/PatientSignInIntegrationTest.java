package org.forafdate.msauth.signin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.forafdate.msauth.sessions.AuthSessionEntity;
import org.forafdate.msauth.sessions.AuthSessionRepository;
import org.forafdate.msauth.sessions.SessionTokenGenerator;
import org.forafdate.msauth.users.AccountStatus;
import org.forafdate.msauth.users.Role;
import org.forafdate.msauth.users.UserEntity;
import org.forafdate.msauth.users.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class PatientSignInIntegrationTest {

    private static final String PASSWORD = "Example password 42";

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
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SessionTokenGenerator tokenGenerator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void signsInPatientAndPersistsHashedSession() throws Exception {
        registerPatient("patient.signin");
        ResponseEntity<String> response = postLogin("patient.signin", PASSWORD);

        assertEquals(HttpStatus.OK.value(), response.getStatusCode().value());
        JsonNode body = objectMapper.readTree(response.getBody());
        String token = body.get("token").asText();
        assertEquals("patient.signin", body.get("username").asText());
        assertFalse(token.isBlank());

        String hash = tokenGenerator.hash(token);
        assertEquals(64, hash.length());
        assertNotEquals(token, hash);
        AuthSessionEntity session = authSessionRepository.findByTokenHash(hash).orElseThrow();
        assertEquals(hash, session.getTokenHash());
        assertEquals("patient.signin", session.getUser().getUsername());
        assertNull(session.getRevokedAt());
        assertTrue(session.getExpiresAt().isAfter(OffsetDateTime.now().plusMinutes(1439)));
    }

    @Test
    void rejectsUnknownUsername() throws Exception {
        assertError(
                postLogin("unknown.patient", PASSWORD),
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "INVALID_CREDENTIALS",
                null
        );
    }

    @Test
    void rejectsWrongPassword() throws Exception {
        registerPatient("wrong.password");
        assertError(
                postLogin("wrong.password", "Wrong password 42"),
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "INVALID_CREDENTIALS",
                null
        );
    }

    @ParameterizedTest
    @MethodSource("nonActiveAccounts")
    void rejectsInactiveAndDisabledAccounts(AccountStatus status, String code, String username) throws Exception {
        saveUser(username, Role.PATIENT, status);
        assertError(postLogin(username, PASSWORD), HttpStatus.FORBIDDEN.value(), code, null);
    }

    @Test
    void rejectsStaffAccountOnPatientLogin() throws Exception {
        saveUser("staff.member", Role.RECEPTIONIST, AccountStatus.ACTIVE);
        assertError(
                postLogin("staff.member", PASSWORD),
                HttpStatus.FORBIDDEN.value(),
                "ACCOUNT_TYPE_FORBIDDEN",
                null
        );
    }

    @Test
    void rejectsMissingUsername() throws Exception {
        assertError(
                postLogin(null, PASSWORD),
                HttpStatus.BAD_REQUEST.value(),
                "REQUIRED",
                "username"
        );
    }

    @Test
    void rejectsMissingPassword() throws Exception {
        assertError(
                postLogin("patient.signin", null),
                HttpStatus.BAD_REQUEST.value(),
                "REQUIRED",
                "password"
        );
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/auth/patients/login",
                new HttpEntity<>("{ not json", headers),
                String.class
        );
        assertError(response, HttpStatus.BAD_REQUEST.value(), "MALFORMED_REQUEST", null);
    }

    @Test
    void acceptsValidSessionOnProtectedRequest() throws Exception {
        String username = "me.patient";
        registerPatient(username);
        String token = signInAndGetToken(username);

        ResponseEntity<String> response = getMe("Bearer " + token);

        assertEquals(HttpStatus.OK.value(), response.getStatusCode().value());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals(username, body.get("username").asText());
    }

    @Test
    void rejectsExpiredSession() throws Exception {
        String username = "expired.patient";
        registerPatient(username);
        UserEntity user = userRepository.findByUsername(username).orElseThrow();
        String token = "expired.session.token";
        OffsetDateTime now = OffsetDateTime.now();
        authSessionRepository.saveAndFlush(new AuthSessionEntity(
                UUID.randomUUID(),
                user,
                tokenGenerator.hash(token),
                now.minusMinutes(10),
                now.minusMinutes(1)
        ));

        assertError(getMe("Bearer " + token), HttpStatus.UNAUTHORIZED.value(), "SESSION_EXPIRED", null);
    }

    @Test
    void rejectsMissingInvalidAndRevokedSessions() throws Exception {
        assertError(getMe(null), HttpStatus.UNAUTHORIZED.value(), "SESSION_REQUIRED", null);
        assertError(getMe("Basic xyz"), HttpStatus.UNAUTHORIZED.value(), "SESSION_REQUIRED", null);
        assertError(getMe("Bearer unknown-token"), HttpStatus.UNAUTHORIZED.value(), "SESSION_INVALID", null);

        String username = "revoked.patient";
        registerPatient(username);
        UserEntity user = userRepository.findByUsername(username).orElseThrow();
        String token = "revoked.session.token";
        OffsetDateTime now = OffsetDateTime.now();
        authSessionRepository.saveAndFlush(new AuthSessionEntity(
                UUID.randomUUID(),
                user,
                tokenGenerator.hash(token),
                now.minusMinutes(5),
                now.plusHours(1),
                now.minusMinutes(1)
        ));

        assertError(getMe("Bearer " + token), HttpStatus.UNAUTHORIZED.value(), "SESSION_INVALID", null);
    }

    private void registerPatient(String username) {
        ResponseEntity<String> response = postRegistration(username, PASSWORD, PASSWORD);
        assertEquals(HttpStatus.CREATED.value(), response.getStatusCode().value());
    }

    private String signInAndGetToken(String username) throws Exception {
        ResponseEntity<String> response = postLogin(username, PASSWORD);
        assertEquals(HttpStatus.OK.value(), response.getStatusCode().value());
        return objectMapper.readTree(response.getBody()).get("token").asText();
    }

    private UserEntity saveUser(String username, Role role, AccountStatus status) {
        return userRepository.saveAndFlush(new UserEntity(
                UUID.randomUUID(),
                username,
                passwordEncoder.encode(PASSWORD),
                role == Role.PATIENT ? null : "Staff Member",
                role,
                status
        ));
    }

    private ResponseEntity<String> postRegistration(String username, String password, String confirmation) {
        Map<String, String> payload = new LinkedHashMap<>();
        putIfPresent(payload, "username", username);
        putIfPresent(payload, "password", password);
        putIfPresent(payload, "passwordConfirmation", confirmation);
        return postJson("/api/auth/patients/registration", payload);
    }

    private ResponseEntity<String> postLogin(String username, String password) {
        Map<String, String> payload = new LinkedHashMap<>();
        putIfPresent(payload, "username", username);
        putIfPresent(payload, "password", password);
        return postJson("/api/auth/patients/login", payload);
    }

    private ResponseEntity<String> postJson(String path, Map<String, String> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(path, new HttpEntity<>(toJson(payload), headers), String.class);
    }

    private ResponseEntity<String> getMe(String authorization) {
        HttpHeaders headers = new HttpHeaders();
        if (authorization != null) {
            headers.set("Authorization", authorization);
        }
        return restTemplate.exchange(
                "/api/auth/patients/me",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
    }

    private String toJson(Map<String, String> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void putIfPresent(Map<String, String> payload, String key, String value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private void assertError(ResponseEntity<String> response, int status, String code, String field)
            throws Exception {
        assertEquals(status, response.getStatusCode().value());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals(code, body.get("code").asText());
        if (field == null) {
            assertFalse(body.has("field"));
        } else {
            assertEquals(field, body.get("field").asText());
        }
    }

    private static Stream<Arguments> nonActiveAccounts() {
        return Stream.of(
                Arguments.of(AccountStatus.INACTIVE, "ACCOUNT_INACTIVE", "inactive.patient"),
                Arguments.of(AccountStatus.DISABLED, "ACCOUNT_DISABLED", "disabled.patient")
        );
    }
}
