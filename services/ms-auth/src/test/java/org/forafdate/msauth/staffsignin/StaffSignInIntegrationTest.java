package org.forafdate.msauth.staffsignin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
class StaffSignInIntegrationTest {

    private static final String PASSWORD = "Example password 42";
    private static final String LOGIN_PATH = "/api/auth/staff/login";

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

    @ParameterizedTest
    @MethodSource("staffRoles")
    void signsInStaffRoleAndPersistsHashedSession(Role role, String name) throws Exception {
        String username = "staff.signin." + role.name().toLowerCase();
        saveUser(username, role, name, AccountStatus.ACTIVE);

        ResponseEntity<String> response = postLogin(username, PASSWORD);

        assertEquals(HttpStatus.OK.value(), response.getStatusCode().value());
        JsonNode body = objectMapper.readTree(response.getBody());
        String token = body.get("token").asText();
        assertEquals(name, body.get("name").asText());
        assertEquals(username, body.get("username").asText());
        assertEquals(role.name(), body.get("role").asText());
        assertFalse(token.isBlank());

        String hash = tokenGenerator.hash(token);
        assertEquals(64, hash.length());
        assertNotEquals(token, hash);
        AuthSessionEntity session = authSessionRepository.findByTokenHash(hash).orElseThrow();
        assertEquals(hash, session.getTokenHash());
        assertEquals(username, session.getUser().getUsername());
        assertNull(session.getRevokedAt());
        assertTrue(session.getExpiresAt().isAfter(OffsetDateTime.now().plusMinutes(1439)));
    }

    @Test
    void rejectsPatientAccountOnStaffLogin() throws Exception {
        saveUser("staff.login.patient", Role.PATIENT, null, AccountStatus.ACTIVE);
        assertError(
                postLogin("staff.login.patient", PASSWORD),
                HttpStatus.FORBIDDEN.value(),
                "ACCOUNT_TYPE_FORBIDDEN",
                null
        );
    }

    @Test
    void rejectsUnknownUsername() throws Exception {
        assertError(
                postLogin("unknown.staff", PASSWORD),
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "INVALID_CREDENTIALS",
                null
        );
    }

    @Test
    void rejectsWrongPassword() throws Exception {
        saveUser("wrong.password.staff", Role.RECEPTIONIST, "Lucía Torres", AccountStatus.ACTIVE);
        assertError(
                postLogin("wrong.password.staff", "Wrong password 42"),
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "INVALID_CREDENTIALS",
                null
        );
    }

    @ParameterizedTest
    @MethodSource("nonActiveAccounts")
    void rejectsInactiveAndDisabledAccounts(AccountStatus status, String code, String username) throws Exception {
        saveUser(username, Role.RECEPTIONIST, "Lucía Torres", status);
        assertError(postLogin(username, PASSWORD), HttpStatus.FORBIDDEN.value(), code, null);
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
                postLogin("staff.login.missing.password", null),
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
                LOGIN_PATH,
                new HttpEntity<>("{ not json", headers),
                String.class
        );
        assertError(response, HttpStatus.BAD_REQUEST.value(), "MALFORMED_REQUEST", null);
    }

    @Test
    void revokedStaffTokenLosesAccessToProtectedRoute() throws Exception {
        String username = "staff.logout";
        saveUser(username, Role.ADMINISTRATOR, "Ana Ruiz", AccountStatus.ACTIVE);
        String token = signInAndGetToken(username);

        assertEquals(HttpStatus.NO_CONTENT.value(), postLogout("Bearer " + token).getStatusCode().value());
        AuthSessionEntity session = authSessionRepository.findByTokenHash(tokenGenerator.hash(token)).orElseThrow();
        assertNotNull(session.getRevokedAt());

        assertError(
                postLogout("Bearer " + token),
                HttpStatus.UNAUTHORIZED.value(),
                "SESSION_INVALID",
                null
        );
    }

    private void saveUser(String username, Role role, String name, AccountStatus status) {
        userRepository.saveAndFlush(new UserEntity(
                UUID.randomUUID(),
                username,
                passwordEncoder.encode(PASSWORD),
                name,
                role,
                status
        ));
    }

    private String signInAndGetToken(String username) throws Exception {
        ResponseEntity<String> response = postLogin(username, PASSWORD);
        assertEquals(HttpStatus.OK.value(), response.getStatusCode().value());
        return objectMapper.readTree(response.getBody()).get("token").asText();
    }

    private ResponseEntity<String> postLogin(String username, String password) {
        Map<String, String> payload = new LinkedHashMap<>();
        putIfPresent(payload, "username", username);
        putIfPresent(payload, "password", password);
        return postJson(LOGIN_PATH, payload);
    }

    private ResponseEntity<String> postLogout(String authorization) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authorization);
        return restTemplate.exchange(
                "/api/auth/logout",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class
        );
    }

    private ResponseEntity<String> postJson(String path, Map<String, String> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(path, new HttpEntity<>(toJson(payload), headers), String.class);
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

    private static Stream<Arguments> staffRoles() {
        return Stream.of(
                Arguments.of(Role.ADMINISTRATOR, "Ana Ruiz"),
                Arguments.of(Role.RECEPTIONIST, "Lucía Torres"),
                Arguments.of(Role.OPHTHALMOLOGIST, "Mateo Silva")
        );
    }

    private static Stream<Arguments> nonActiveAccounts() {
        return Stream.of(
                Arguments.of(AccountStatus.INACTIVE, "ACCOUNT_INACTIVE", "inactive.staff"),
                Arguments.of(AccountStatus.DISABLED, "ACCOUNT_DISABLED", "disabled.staff")
        );
    }
}
