package org.forafdate.msauth.staffsignin;

import jakarta.validation.constraints.NotBlank;

public record StaffSignInRequest(
        @NotBlank(message = "REQUIRED") String username,
        @NotBlank(message = "REQUIRED") String password
) {
}
