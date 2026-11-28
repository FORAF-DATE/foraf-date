package org.forafdate.msauth.sessions;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.forafdate.msauth.error.ApiException;
import org.forafdate.msauth.error.ErrorCode;
import org.forafdate.msauth.users.AccountStatus;
import org.forafdate.msauth.users.Role;
import org.forafdate.msauth.users.UserEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SessionService {

    private final AuthSessionRepository authSessionRepository;
    private final SessionTokenGenerator tokenGenerator;
    private final Duration ttl;

    public SessionService(AuthSessionRepository authSessionRepository,
                          SessionTokenGenerator tokenGenerator,
                          @Value("${auth.session.ttl-minutes}") long ttlMinutes) {
        this.authSessionRepository = authSessionRepository;
        this.tokenGenerator = tokenGenerator;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public IssuedSession issue(UserEntity user) {
        OffsetDateTime createdAt = OffsetDateTime.now();
        String token = tokenGenerator.generate();
        AuthSessionEntity session = new AuthSessionEntity(
                UUID.randomUUID(),
                user,
                tokenGenerator.hash(token),
                createdAt,
                createdAt.plus(ttl)
        );
        authSessionRepository.saveAndFlush(session);
        return new IssuedSession(token, session);
    }

    @Transactional(readOnly = true)
    public UserEntity requireActivePatient(String authorizationHeader) {
        String token = extractToken(authorizationHeader);
        AuthSessionEntity session = authSessionRepository.findByTokenHash(tokenGenerator.hash(token))
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_INVALID, null));
        if (session.getRevokedAt() != null) {
            throw new ApiException(ErrorCode.SESSION_INVALID, null);
        }
        if (!session.getExpiresAt().isAfter(OffsetDateTime.now())) {
            throw new ApiException(ErrorCode.SESSION_EXPIRED, null);
        }
        UserEntity user = session.getUser();
        if (user.getStatus() != AccountStatus.ACTIVE || user.getRole() != Role.PATIENT) {
            throw new ApiException(ErrorCode.SESSION_INVALID, null);
        }
        return user;
    }

    private String extractToken(String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ApiException(ErrorCode.SESSION_REQUIRED, null);
        }
        String token = authorizationHeader.substring(7).trim();
        if (token.isEmpty()) {
            throw new ApiException(ErrorCode.SESSION_REQUIRED, null);
        }
        return token;
    }

    public record IssuedSession(String token, AuthSessionEntity session) {
    }
}
