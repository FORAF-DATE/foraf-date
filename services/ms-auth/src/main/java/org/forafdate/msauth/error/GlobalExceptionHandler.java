package org.forafdate.msauth.error;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        List<ObjectError> errors = exception.getBindingResult().getAllErrors();
        if (errors.isEmpty()) {
            return ResponseEntity.badRequest().body(new ApiError(ErrorCode.MALFORMED_REQUEST, null));
        }
        ObjectError error = errors.get(0);
        ErrorCode code = parseCode(error.getDefaultMessage());
        String field = error instanceof FieldError fieldError
                ? fieldError.getField()
                : (code == ErrorCode.PASSWORD_MISMATCH ? "passwordConfirmation" : null);
        return ResponseEntity.badRequest().body(new ApiError(code, field));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException exception) {
        return ResponseEntity.status(statusFor(exception.code()))
                .body(new ApiError(exception.code(), exception.field()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ApiError> handleMalformedRequest(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError(ErrorCode.MALFORMED_REQUEST, null));
    }

    private HttpStatus statusFor(ErrorCode code) {
        return switch (code) {
            case USERNAME_TAKEN -> HttpStatus.CONFLICT;
            case INVALID_CREDENTIALS -> HttpStatus.UNPROCESSABLE_ENTITY;
            case ACCOUNT_INACTIVE, ACCOUNT_DISABLED, ACCOUNT_TYPE_FORBIDDEN -> HttpStatus.FORBIDDEN;
            case SESSION_REQUIRED, SESSION_INVALID, SESSION_EXPIRED -> HttpStatus.UNAUTHORIZED;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private ErrorCode parseCode(String message) {
        if (message == null) {
            return ErrorCode.MALFORMED_REQUEST;
        }
        try {
            return ErrorCode.valueOf(message);
        } catch (IllegalArgumentException exception) {
            return ErrorCode.MALFORMED_REQUEST;
        }
    }
}
