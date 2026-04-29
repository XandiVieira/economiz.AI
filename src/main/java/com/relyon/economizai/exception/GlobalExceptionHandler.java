package com.relyon.economizai.exception;

import com.relyon.economizai.service.LocalizedMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final LocalizedMessageService messageService;

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        return respond(ex, HttpStatus.CONFLICT, "Registration attempt with existing email");
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        return respond(ex, HttpStatus.UNAUTHORIZED, "Failed login attempt");
    }

    @ExceptionHandler(InvalidCurrentPasswordException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCurrentPassword(InvalidCurrentPasswordException ex) {
        return respond(ex, HttpStatus.BAD_REQUEST, "Invalid current password");
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        return respond(ex, HttpStatus.NOT_FOUND, "User not found");
    }

    @ExceptionHandler({HouseholdNotFoundException.class, ReceiptNotFoundException.class, ReceiptItemNotFoundException.class, ProductNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(DomainException ex) {
        return respond(ex, HttpStatus.NOT_FOUND, "Entity not found");
    }

    @ExceptionHandler({InvalidInviteCodeException.class, InvalidQrPayloadException.class, UnsupportedStateException.class, ReceiptParseException.class, ReceiptNotEditableException.class, AlreadyInHouseholdException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(DomainException ex) {
        return respond(ex, HttpStatus.BAD_REQUEST, "Bad request");
    }

    @ExceptionHandler({ReceiptAlreadyIngestedException.class, ProductAliasConflictException.class, EanConflictException.class})
    public ResponseEntity<ErrorResponse> handleConflict(DomainException ex) {
        return respond(ex, HttpStatus.CONFLICT, "Conflict");
    }

    @ExceptionHandler(SefazFetchException.class)
    public ResponseEntity<ErrorResponse> handleSefazFetch(SefazFetchException ex) {
        return respond(ex, HttpStatus.BAD_GATEWAY, "SEFAZ fetch failed");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        var errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, f -> f.getDefaultMessage() != null ? f.getDefaultMessage() : "invalid"));
        var message = messageService.translate("validation.failed");
        log.warn("Validation failed: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), message, LocalDateTime.now(), errors));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        var message = messageService.translate("error.internal");
        log.error("Unexpected error: {}: {}", ex.getClass().getName(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), message, LocalDateTime.now()));
    }

    private ResponseEntity<ErrorResponse> respond(DomainException ex, HttpStatus status, String logContext) {
        var message = messageService.translate(ex);
        log.warn("{}: {}", logContext, message);
        return ResponseEntity.status(status)
                .body(new ErrorResponse(status.value(), message, LocalDateTime.now()));
    }

    public record ErrorResponse(int status, String message, LocalDateTime timestamp, Map<String, String> errors) {
        public ErrorResponse(int status, String message, LocalDateTime timestamp) {
            this(status, message, timestamp, null);
        }
    }
}
