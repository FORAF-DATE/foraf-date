package org.forafdate.msauth.staffsignin;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/staff")
public class StaffSignInController {

    private final StaffSignInService staffSignInService;

    public StaffSignInController(StaffSignInService staffSignInService) {
        this.staffSignInService = staffSignInService;
    }

    @PostMapping("/login")
    public ResponseEntity<StaffSignInResponse> signIn(
            @Valid @RequestBody StaffSignInRequest request
    ) {
        return ResponseEntity.ok(staffSignInService.signIn(request));
    }
}
