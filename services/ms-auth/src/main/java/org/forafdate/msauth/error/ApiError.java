package org.forafdate.msauth.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"code", "field"})
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(ErrorCode code, String field) {
}
