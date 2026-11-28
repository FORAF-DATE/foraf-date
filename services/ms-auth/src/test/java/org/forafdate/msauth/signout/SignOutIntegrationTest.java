package org.forafdate.msauth.signout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;
import org.forafdate.msauth.sessions.AuthSessionEntity;
import org.forafdate.msauth.sessions.AuthSessionRepository;
import org.forafdate.msauth.sessions.SessionTokenGenerator;
import org.junit.jupiter.api.Test;
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
class SignOutIntegrationTest {

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
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private SessionTokenGenerator tokenGenerator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void revokesPresentedSessionAndKeepsOtherSessionsValid() throws Exception {
        String username = "logout.patient";
        registerPatient(username);
        String revokedToken = signInAndGetToken(username);
        String activeToken = signInAndGetToken(username);

        ResponseEntity<String> logoutResponse = postLogout("Bearer " + revokedToken);
        assertEquals(HttpStatus.NO_CONTENT.value(), logoutResponse.getStatusCode().value());
        assertNull(logoutResponse.getBody());

        AuthSessionEntity revokedSession = authSessionRepository
                .findByTokenHash(tokenGenerator.hash(revokedToken))
                .orElseThrow();
        AuthSessionEntity activeSession = authSessionRepository
                .findByTokenHash(tokenGenerator.hash(activeToken))
                .orElseThrow();
        assertNotNull(revokedSession.getRevokedAt());
        assertNull(activeSession.getRevokedAt());

        assertError(getMe("Bearer " + revokedToken), HttpStatus.UNAUTHORIZED.value(), "SESSION_INVALID", null);

        ResponseEntity<String> activeResponse = getMe("Bearer " + activeToken);
        assertEquals(HttpStatus.OK.value(), activeResponse.getStatusCode().value());
        JsonNode body = objectMapper.readTree(activeResponse.getBody());
        assertEquals(username, body.get("username").asText());
    }

    @Test
    void rejectsLogoutWithoutBearerToken() throws Exception {
        assertError(postLogout(null), HttpStatus.UNAUTHORIZED.value(), "SESSION_REQUIRED", null);
    }

    private void registerPatient(String username) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("password", PASSWORD);
        payload.put("passwordConfirmation", PASSWORD);
        ResponseEntity<String> response = postJson("/api/auth/patients/registration", payload);
        assertEquals(HttpStatus.CREATED.value(), response.getStatusCode().value());
    }

    private String signInAndGetToken(String username) throws Exception {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("password", PASSWORD);
        ResponseEntity<String> response = postJson("/api/auth/patients/login", payload);
        assertEquals(HttpStatus.OK.value(), response.getStatusCode().value());
        return objectMapper.readTree(response.getBody()).get("token").asText();
    }

    private ResponseEntity<String> postJson(String path, Map<String, String> payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(path, new HttpEntity<>(toJson(payload), headers), String.class);
    }

    private ResponseEntity<String> postLogout(String authorization) {
        HttpHeaders headers = new HttpHeaders();
        if (authorization != null) {
            headers.set("Authorization", authorization);
        }
        return restTemplate.exchange(
                "/api/auth/logout",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class
        );
    }

    private ResponseEntity<String> getMe(String authorization) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", authorization);
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

    private void assertError(ResponseEntity<String> response, int status, String code, String field)
            throws Exception {
        assertEquals(status, response.getStatusCode().value());
        JsonNode body = objectMapper.readTree(response.getBody());
        assertEquals(code, body.get("code").asText());
        if (field == null) {
            assertNull(body.get("field"));
        } else {
            assertEquals(field, body.get("field").asText());
        }
    }
}
