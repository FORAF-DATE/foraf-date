package org.forafdate.msauth.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.forafdate.msauth.registration.PatientRegistrationRequest;

public class PasswordMatchesValidator implements ConstraintValidator<PasswordMatches, PatientRegistrationRequest> {

    @Override
    public boolean isValid(PatientRegistrationRequest value, ConstraintValidatorContext context) {
        if (value == null || value.password() == null || value.passwordConfirmation() == null) {
            return true;
        }
        return value.password().equals(value.passwordConfirmation());
    }
}
