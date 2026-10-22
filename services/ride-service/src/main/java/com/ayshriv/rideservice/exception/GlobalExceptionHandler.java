package com.ayshriv.rideservice.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extends {@link ResponseEntityExceptionHandler} so framework errors (unknown path, wrong HTTP
 * method, ...) keep their proper 4xx status instead of falling into the catch-all 500 below.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Body validation failures: field -> message. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(errors);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        return ResponseEntity.badRequest().body(Map.of("error", "Malformed request body"));
    }

    @ExceptionHandler(RideNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(RideNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(InvalidRideStateException.class)
    public ResponseEntity<Map<String, String>> handleInvalidState(InvalidRideStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(ActiveRideExistsException.class)
    public ResponseEntity<Map<String, String>> handleActiveRideExists(ActiveRideExistsException ex) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "ACTIVE_RIDE_EXISTS");
        body.put("message", ex.getMessage());
        body.put("rideId", ex.getRideId());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(InvalidRideRequestException.class)
    public ResponseEntity<Map<String, String>> handleInvalidRequest(InvalidRideRequestException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    /** Two requests changed the same ride concurrently; the loser can simply retry. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Concurrent ride update rejected: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Ride was modified concurrently, please retry"));
    }

    /** Catch-all: log the details, but never leak them to the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error"));
    }
}
