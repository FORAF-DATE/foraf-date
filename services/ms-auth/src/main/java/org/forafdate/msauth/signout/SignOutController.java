package org.forafdate.msauth.signout;

import org.forafdate.msauth.sessions.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class SignOutController {

    private final SessionService sessionService;

    public SignOutController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> signOut(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        sessionService.revoke(authorization);
        return ResponseEntity.noContent().build();
    }
}
