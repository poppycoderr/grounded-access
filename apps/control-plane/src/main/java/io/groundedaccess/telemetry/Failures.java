package io.groundedaccess.telemetry;

import java.sql.SQLException;

import org.springframework.web.client.RestClientResponseException;

/**
 * What a log line may say about an exception. Exception messages are never logged: a rejected model-service call quotes the text it was sent,
 * and a database error can quote the values of a row. The class name, an HTTP status and an SQL state say what failed without quoting data.
 */
public final class Failures {

    private Failures() {
    }

    public static String describe(Throwable failure) {
        StringBuilder description = new StringBuilder(failure.getClass().getSimpleName());
        if (failure instanceof RestClientResponseException response) {
            description.append(" status=").append(response.getStatusCode().value());
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                description.append(" sqlState=").append(sql.getSQLState());
                break;
            }
        }
        return description.toString();
    }
}
