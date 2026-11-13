package com.grocery.common;

import com.grocery.common.Exceptions.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private ResponseEntity<Map<String, Object>> body(HttpStatus s, String msg, Object details) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", Instant.now().toString());
        m.put("status", s.value());
        m.put("message", msg);
        if (details != null) m.put("details", details);
        return ResponseEntity.status(s).body(m);
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<?> notFound(NotFoundException e) { return body(HttpStatus.NOT_FOUND, e.getMessage(), null); }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<?> business(BusinessException e) { return body(HttpStatus.CONFLICT, e.getMessage(), null); }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        Map<String, String> f = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(x -> f.put(x.getField(), x.getDefaultMessage()));
        return body(HttpStatus.BAD_REQUEST, "Validation failed", f);
    }

    @ExceptionHandler({BadCredentialsException.class, DisabledException.class})
    ResponseEntity<?> badCreds(Exception e) { return body(HttpStatus.UNAUTHORIZED, "Invalid username or password", null); }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<?> denied(AccessDeniedException e) { return body(HttpStatus.FORBIDDEN, "Access denied", null); }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> integrity(DataIntegrityViolationException e) {
        return body(HttpStatus.CONFLICT, "Data integrity violation (duplicate or invalid reference)", null);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<?> badRequest(Exception e) { return body(HttpStatus.BAD_REQUEST, "Malformed request: " + e.getMessage(), null); }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooBig(Exception e) { return body(HttpStatus.PAYLOAD_TOO_LARGE, "File too large (max 5 MB)", null); }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method(Exception e) { return body(HttpStatus.METHOD_NOT_ALLOWED, e.getMessage(), null); }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<?> noResource(Exception e) { return body(HttpStatus.NOT_FOUND, "Not found", null); }

    @ExceptionHandler({PessimisticLockingFailureException.class, OptimisticLockingFailureException.class})
    ResponseEntity<?> lock(Exception e) { return body(HttpStatus.CONFLICT, "The item is being updated by someone else - please retry", null); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> other(Exception e) {
        log.error("Unhandled exception", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", null);
    }
}
