package org.forafdate.msauth.registration;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/patients")
public class PatientRegistrationController {

    private final PatientRegistrationService patientRegistrationService;

    public PatientRegistrationController(PatientRegistrationService patientRegistrationService) {
        this.patientRegistrationService = patientRegistrationService;
    }

    @PostMapping("/registration")
    public ResponseEntity<PatientRegistrationResponse> registerPatient(
            @Valid @RequestBody PatientRegistrationRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new PatientRegistrationResponse(patientRegistrationService.registerPatient(request)));
    }
}
