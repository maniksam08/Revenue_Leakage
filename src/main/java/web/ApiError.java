package web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** Uniform error body for every failure. {@code errorId} is set only for 500s and matches the server log line. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(Instant timestamp, int status, String error, String code, String message, String path,
                       List<FieldIssue> issues, String errorId) {

    public record FieldIssue(String field, String message) {}
}
