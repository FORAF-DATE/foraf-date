package org.forafdate.msauth.signin;

import jakarta.validation.Valid;
import org.forafdate.msauth.sessions.SessionService;
import org.forafdate.msauth.users.UserEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/patients")
public class PatientSignInController {

    private final PatientSignInService patientSignInService;
    private final SessionService sessionService;

    public PatientSignInController(PatientSignInService patientSignInService,
                                   SessionService sessionService) {
        this.patientSignInService = patientSignInService;
        this.sessionService = sessionService;
    }

    @PostMapping("/login")
    public ResponseEntity<PatientSignInResponse> signIn(
            @Valid @RequestBody PatientSignInRequest request
    ) {
        return ResponseEntity.ok(patientSignInService.signIn(request));
    }

    @GetMapping("/me")
    public ResponseEntity<PatientMeResponse> currentPatient(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        UserEntity user = sessionService.requireActivePatient(authorization);
        return ResponseEntity.ok(new PatientMeResponse(user.getUsername()));
    }
}
