package io.groundedaccess.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

/**
 * Maps dependency failures to problem responses without exposing internal messages.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidCursorException.class)
    ProblemDetail invalidCursor(InvalidCursorException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The cursor is not valid");
        problem.setProperty("code", "INVALID_CURSOR");
        return problem;
    }

    @ExceptionHandler(RestClientException.class)
    ProblemDetail modelServiceUnavailable(RestClientException exception) {
        log.warn("Model service call failed: {}", exception.toString());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "The model service is unavailable");
        problem.setProperty("code", "MODEL_SERVICE_UNAVAILABLE");
        return problem;
    }
}
