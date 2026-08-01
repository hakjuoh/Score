package org.oagi.score.gateway.http.configuration.handler;

import org.oagi.score.gateway.http.api.DataAccessForbiddenException;
import org.oagi.score.gateway.http.api.ai_management.execution.AiSharedStateUnavailableException;
import org.oagi.score.gateway.http.api.ai_management.policy.exception.AiPolicyErrorCode;
import org.oagi.score.gateway.http.api.ai_management.policy.exception.AiPolicyViolationException;
import org.oagi.score.gateway.http.api.ai_management.policy.exception.AiQuotaExceededException;
import org.oagi.score.gateway.http.common.model.AccessControlException;
import org.oagi.score.gateway.http.common.model.NotFoundException;
import org.oagi.score.gateway.http.common.model.base.ScoreDataAccessException;
import org.oagi.score.gateway.http.security.secret.ApplicationSecretUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.sql.SQLSyntaxErrorException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ScoreResponseEntityExceptionHandler extends ResponseEntityExceptionHandler {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Flattens backend error messages so they are safe to expose through HTTP
     * headers such as X-Error-Message, which cannot contain CR/LF characters.
     */
    private String toHeaderValue(String message) {
        if (message == null) {
            return null;
        }
        return message
                .replace("\r\n", " ")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
    }

    private ResponseEntity<String> errorResponse(HttpStatus status, String message) {
        // Spring Framework 7: HttpHeaders no longer implements MultiValueMap (#1750).
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Error-Message", toHeaderValue(message));
        return new ResponseEntity<>(message, headers, status);
    }

    private ResponseEntity<String> errorResponse(HttpStatus status, String message, String errorMessageId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Error-Message", toHeaderValue(message));
        if (errorMessageId != null) {
            headers.set("X-Error-Message-Id", errorMessageId);
        }
        return new ResponseEntity<>(message, headers, status);
    }

    private ResponseEntity<String> policyErrorResponse(HttpStatus status,
                                                       AiPolicyViolationException exception) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Error-Message", toHeaderValue(exception.getMessage()));
        headers.set("X-Error-Code", exception.code().name());
        if (exception instanceof AiQuotaExceededException quota
                && quota.retryAfter() != null && !quota.retryAfter().isNegative()) {
            headers.set("Retry-After", Long.toString(Math.max(1L,
                    quota.retryAfter().toSeconds())));
        }
        return new ResponseEntity<>(exception.getMessage(), headers, status);
    }

    @ExceptionHandler(AiPolicyViolationException.class)
    public ResponseEntity<String> handleAiPolicyViolationException(
            AiPolicyViolationException ex, WebRequest webRequest) {
        logger.debug(ex.getMessage(), ex);
        HttpStatus status = switch (ex.code()) {
            case AI_QUOTA_EXHAUSTED, AI_REQUEST_TOKEN_LIMIT_EXHAUSTED,
                    AI_ACTIVE_REQUEST_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
            case AI_POLICY_VERSION_CONFLICT -> HttpStatus.CONFLICT;
            case AI_PROVIDER_NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            case AI_DISABLED_BY_POLICY, AI_MODEL_NOT_ALLOWED,
                    AI_REASONING_EFFORT_NOT_ALLOWED, AI_NO_ALLOWED_MODELS -> HttpStatus.FORBIDDEN;
        };
        return policyErrorResponse(status, ex);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity handleAuthenticationException(
            AuthenticationException ex, WebRequest webRequest) {
        logger.debug(ex.getMessage(), ex);
        return new ResponseEntity(HttpStatus.UNAUTHORIZED);
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity handleEmptyResultDataAccessException(
            EmptyResultDataAccessException ex, WebRequest webRequest) {
        logger.debug(ex.getMessage(), ex);
        return new ResponseEntity(HttpStatus.NOT_FOUND);
    }

    /**
     * Handles controller-level not-found signals
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity handleNotFoundException(
            NotFoundException ex, WebRequest webRequest) {
        logger.debug(ex.getMessage(), ex);
        return new ResponseEntity(HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(AccessControlException.class)
    public ResponseEntity handleAccessControlException(
            AccessControlException ex, WebRequest webRequest) {
        logger.debug(ex.getMessage(), ex);
        return errorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity handleIllegalArgumentException(
            IllegalArgumentException ex, WebRequest webRequest) {
        logger.debug(ex.getMessage(), ex);
        return errorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(ApplicationSecretUnavailableException.class)
    public ResponseEntity<String> handleApplicationSecretUnavailableException(
            ApplicationSecretUnavailableException ex, WebRequest webRequest) {
        logger.debug(ex.getMessage(), ex);
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity handleIllegalStateException(
            IllegalStateException ex, WebRequest webRequest) {
        logger.debug(ex.getMessage(), ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
    }

    /**
     * The shared AI request state is momentarily unreachable. The web client
     * retries its recovery poll instead of waiting for a response that a
     * blocked lock would never produce.
     */
    @ExceptionHandler(AiSharedStateUnavailableException.class)
    public ResponseEntity handleAiSharedStateUnavailableException(
            AiSharedStateUnavailableException ex, WebRequest webRequest) {
        logger.warn(ex.getMessage(), ex);
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(DataAccessForbiddenException.class)
    public ResponseEntity handleDataAccessForbiddenException(
            DataAccessForbiddenException ex, WebRequest webRequest) {
        logger.debug(ex.getMessage(), ex);
        return errorResponse(HttpStatus.FORBIDDEN, ex.getMessage(),
                ex.getErrorMessageId() != null ? ex.getErrorMessageId().toString() : null);
    }

    /**
     * Defense-in-depth safety net: any foreign-key / integrity violation that is not pre-empted by an
     * explicit in-use guard is surfaced as a clean 409 CONFLICT instead of a raw 500.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity handleDataIntegrityViolationException(
            DataIntegrityViolationException ex, WebRequest webRequest) {
        logger.debug(ex.getMessage(), ex);
        return errorResponse(HttpStatus.CONFLICT,
                "The operation could not be completed because the record is referenced by other data.");
    }

    @ExceptionHandler(BadSqlGrammarException.class)
    public ResponseEntity handleBadSqlGrammarException(
            BadSqlGrammarException ex, WebRequest webRequest) {
        logger.debug(ex.getMessage(), ex);
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(SQLSyntaxErrorException.class)
    public ResponseEntity handleSQLSyntaxErrorException(
            SQLSyntaxErrorException ex, WebRequest webRequest) {
        logger.debug(ex.getMessage(), ex);
        return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(ScoreDataAccessException.class)
    public ResponseEntity handleScoreDataAccessException(
            ScoreDataAccessException ex, WebRequest webRequest) {
        logger.debug(ex.getMessage(), ex);
        return errorResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(OutOfMemoryError.class)
    public ResponseEntity handleOutOfMemoryError(
            OutOfMemoryError ex, WebRequest webRequest) {
        logger.debug(ex.getMessage(), ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "Not enough memory to perform the request: " + ex.getMessage() + ". " +
                        "Please consider either increasing available heap space or cleaning data to reduce the size of the request.");
    }

}
