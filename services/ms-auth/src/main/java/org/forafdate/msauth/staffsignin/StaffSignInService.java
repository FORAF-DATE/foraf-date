package org.forafdate.msauth.staffsignin;

import org.forafdate.msauth.error.ApiException;
import org.forafdate.msauth.error.ErrorCode;
import org.forafdate.msauth.sessions.SessionService;
import org.forafdate.msauth.users.AccountStatus;
import org.forafdate.msauth.users.Role;
import org.forafdate.msauth.users.UserEntity;
import org.forafdate.msauth.users.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class StaffSignInService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessionService;

    public StaffSignInService(UserRepository userRepository,
                              PasswordEncoder passwordEncoder,
                              SessionService sessionService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessionService = sessionService;
    }

    public StaffSignInResponse signIn(StaffSignInRequest request) {
        UserEntity user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS, null));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, null);
        }
        if (user.getStatus() == AccountStatus.INACTIVE) {
            throw new ApiException(ErrorCode.ACCOUNT_INACTIVE, null);
        }
        if (user.getStatus() == AccountStatus.DISABLED) {
            throw new ApiException(ErrorCode.ACCOUNT_DISABLED, null);
        }
        if (user.getRole() == Role.PATIENT) {
            throw new ApiException(ErrorCode.ACCOUNT_TYPE_FORBIDDEN, null);
        }
        String token = sessionService.issue(user).token();
        return new StaffSignInResponse(token, user.getDisplayName(), user.getUsername(), user.getRole());
    }
}
