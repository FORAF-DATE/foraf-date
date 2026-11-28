package org.forafdate.msauth.error;

public class ApiException extends RuntimeException {
    private final ErrorCode code;
    private final String field;

    public ApiException(ErrorCode code, String field) {
        super(code.name());
        this.code = code;
        this.field = field;
    }

    public ErrorCode code() {
        return code;
    }

    public String field() {
        return field;
    }
}
