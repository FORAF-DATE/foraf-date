package org.forafdate.msauth.signin;

import jakarta.validation.constraints.NotBlank;

public record PatientSignInRequest(
        @NotBlank(message = "REQUIRED") String username,
        @NotBlank(message = "REQUIRED") String password
) {
}
