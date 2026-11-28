package org.forafdate.msauth.registration;

import java.util.Objects;
import java.util.UUID;
import org.forafdate.msauth.error.ApiException;
import org.forafdate.msauth.error.ErrorCode;
import org.forafdate.msauth.users.AccountStatus;
import org.forafdate.msauth.users.Role;
import org.forafdate.msauth.users.UserEntity;
import org.forafdate.msauth.users.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PatientRegistrationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public PatientRegistrationService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public String registerPatient(PatientRegistrationRequest request) {
        if (!Objects.equals(request.password(), request.passwordConfirmation())) {
            throw new ApiException(ErrorCode.PASSWORD_MISMATCH, "passwordConfirmation");
        }
        if (userRepository.existsByUsername(request.username())) {
            throw new ApiException(ErrorCode.USERNAME_TAKEN, "username");
        }

        UserEntity user = new UserEntity(
                UUID.randomUUID(),
                request.username(),
                passwordEncoder.encode(request.password()),
                Role.PATIENT,
                AccountStatus.ACTIVE
        );
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(ErrorCode.USERNAME_TAKEN, "username");
        }
        return user.getUsername();
    }
}
