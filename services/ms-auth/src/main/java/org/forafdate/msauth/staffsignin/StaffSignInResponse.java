package org.forafdate.msauth.staffsignin;

import org.forafdate.msauth.users.Role;

public record StaffSignInResponse(
        String token,
        String name,
        String username,
        Role role
) {
}
