package com.relay.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/** Scoped to domain controllers: Actuator and security-filter contracts remain separate. */
@RestControllerAdvice(basePackages="com.relay.api")
public class ApiErrorAdvice {
    public record Detail(String message,String code) {}
    public record Envelope(Detail error) {}
    private ResponseEntity<Envelope> response(int status,String code,String message) {
        return ResponseEntity.status(status).body(new Envelope(new Detail(message,code)));
    }
    @ExceptionHandler(com.relay.workflow.DefinitionException.class)
    ResponseEntity<Envelope> definition(com.relay.workflow.DefinitionException failure) {
        return response(400,failure.reason(),failure.getMessage());
    }
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Envelope> media(Exception ignored) { return response(415,"unsupported_media_type","Use application/json encoded as UTF-8."); }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Envelope> method(Exception ignored) { return response(405,"method_not_allowed","The method is not supported."); }
    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    ResponseEntity<Envelope> locked(Exception ignored) { return response(409,"conflict","The resource state conflicts with this request."); }
    @ExceptionHandler(ApiFailure.class)
    ResponseEntity<Envelope> domain(ApiFailure failure) {
        var r=failure.reason();return response(r.status,r.code,r.message);
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
        HandlerMethodValidationException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Envelope> invalid(Exception ignored) {
        return response(400,"invalid_input","The request is invalid.");
    }
    @ExceptionHandler({DataIntegrityViolationException.class, OptimisticLockingFailureException.class})
    ResponseEntity<Envelope> conflict(Exception ignored) {
        return response(409,"conflict","The resource state conflicts with this request.");
    }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<Envelope> forbidden(Exception ignored) {
        return response(403,"forbidden","Access is denied.");
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Envelope> unexpected(Exception ignored) {
        return response(500,"internal_error","The request could not be completed.");
    }
}
