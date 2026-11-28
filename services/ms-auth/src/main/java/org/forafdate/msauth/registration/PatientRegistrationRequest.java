package org.forafdate.msauth.registration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.forafdate.msauth.validation.PasswordMatches;

@PasswordMatches
public record PatientRegistrationRequest(
        @NotBlank(message = "REQUIRED")
        @Size(min = 4, max = 30, message = "USERNAME_LENGTH")
        String username,
        @NotBlank(message = "REQUIRED")
        @Size(min = 15, max = 128, message = "PASSWORD_LENGTH")
        String password,
        @NotBlank(message = "REQUIRED")
        String passwordConfirmation
) {
}
