package com.freightfox.dispatch.exception;

import com.freightfox.dispatch.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The application's single {@code @RestControllerAdvice}. Every error leaves the API in one shape,
 * {@link ErrorResponse} ({@code {"status":"error","message":...,"errors":[...],"timestamp":...}}).
 * Bad input is always a 4xx; stack traces and exception class names never reach the client (they
 * are logged server-side for unexpected errors only).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String FIELD_ERROR = "%s: %s";
    private static final String LIST_SEPARATOR = ", ";

    /** Bean Validation failures on a {@code @Valid @RequestBody}. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException ex) {
        Stream<String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> String.format(FIELD_ERROR, error.getField(), error.getDefaultMessage()));
        Stream<String> globalErrors = ex.getBindingResult().getGlobalErrors().stream()
                .map(error -> String.format(FIELD_ERROR, error.getObjectName(), error.getDefaultMessage()));
        return validationFailed(Stream.concat(fieldErrors, globalErrors).sorted().toList());
    }

    /** Constraint annotations on controller method parameters, e.g. {@code @Max} on a {@code @RequestParam}. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException ex) {
        if (ex.getStatusCode().is5xxServerError()) {
            return handleUnexpected(ex);
        }
        List<String> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                for (FieldError error : parameterErrors.getFieldErrors()) {
                    errors.add(String.format(FIELD_ERROR, error.getField(), error.getDefaultMessage()));
                }
            } else {
                String name = parameterName(result.getMethodParameter());
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    errors.add(String.format(FIELD_ERROR, name, error.getDefaultMessage()));
                }
            }
        }
        return validationFailed(errors.stream().sorted().toList());
    }

    /** Method validation through {@code @Validated} beans (AOP), e.g. on a service or controller class. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<String> errors = ex.getConstraintViolations().stream()
                .map(violation -> String.format(FIELD_ERROR, path(violation), violation.getMessage()))
                .sorted()
                .toList();
        return validationFailed(errors);
    }

    /** Unparseable body: malformed JSON, wrong types, unknown enum values, missing body. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("Unreadable request body: {}", ex.getMessage());
        ErrorResponse body = JsonErrorTranslator.translate(ex);
        return respond(HttpStatus.BAD_REQUEST, body.message(), body.errors());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return respond(HttpStatus.BAD_REQUEST,
                String.format(ErrorMessages.INVALID_PARAMETER, ErrorMessages.echo(ex.getValue()), ex.getName()),
                List.of(String.format(ErrorMessages.EXPECTED_TYPE_DETAIL, ex.getName(),
                        TypeNames.of(ex.getRequiredType()))));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        return respond(HttpStatus.BAD_REQUEST,
                String.format(ErrorMessages.MISSING_PARAMETER, ex.getParameterName()),
                List.of(String.format(ErrorMessages.MISSING_PARAMETER_DETAIL, ex.getParameterName())));
    }

    /** Domain-level client errors: duplicate IDs, unknown strategy, no vehicles, test-data limits, ... */
    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex) {
        log.debug("Bad request: {}", ex.getMessage());
        return respond(HttpStatus.BAD_REQUEST, ex.getMessage(), ex.getErrors());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        Set<HttpMethod> supported = ex.getSupportedHttpMethods();
        String message = String.format(ErrorMessages.METHOD_NOT_ALLOWED, ErrorMessages.echo(ex.getMethod()));
        if (supported == null || supported.isEmpty()) {
            return respond(HttpStatus.METHOD_NOT_ALLOWED, message, List.of());
        }
        String allowed = supported.stream().map(HttpMethod::name).sorted().collect(Collectors.joining(LIST_SEPARATOR));
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .allow(supported.toArray(HttpMethod[]::new))
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(message, List.of(String.format(ErrorMessages.METHOD_NOT_ALLOWED_DETAIL, allowed))));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        String contentType = ex.getContentType() == null ? ErrorMessages.NONE : ex.getContentType().toString();
        String supported = ex.getSupportedMediaTypes().stream()
                .map(MediaType::toString)
                .collect(Collectors.joining(LIST_SEPARATOR));
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                String.format(ErrorMessages.MEDIA_TYPE_NOT_SUPPORTED, ErrorMessages.echo(contentType)),
                List.of(String.format(ErrorMessages.MEDIA_TYPE_NOT_SUPPORTED_DETAIL, supported)));
    }

    /** The client accepts no representation we can produce, so there is no body we could send. */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Void> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, ErrorMessages.RESOURCE_NOT_FOUND,
                List.of(String.format(ErrorMessages.RESOURCE_NOT_FOUND_DETAIL, request.getMethod(),
                        ErrorMessages.echo(request.getRequestURI()))));
    }

    /** E.g. two concurrent requests inserting the same new ID: the loser can simply retry. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return respond(HttpStatus.CONFLICT, ErrorMessages.DATA_CONFLICT, List.of());
    }

    /**
     * Last resort. Spring MVC exceptions that carry a 4xx status (e.g. a missing path variable or
     * header) keep their status; everything else is a 500 with a generic message.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        if (ex instanceof org.springframework.web.ErrorResponse springError
                && springError.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = springError.getStatusCode();
            String detail = springError.getBody().getDetail();
            String message = detail != null ? detail : HttpStatus.valueOf(status.value()).getReasonPhrase();
            return respond(status, message, List.of());
        }
        log.error("Unexpected error while handling request", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorMessages.UNEXPECTED_ERROR, List.of());
    }

    private static ResponseEntity<ErrorResponse> validationFailed(List<String> errors) {
        return respond(HttpStatus.BAD_REQUEST, ErrorMessages.VALIDATION_FAILED, errors);
    }

    private static ResponseEntity<ErrorResponse> respond(HttpStatusCode status, String message, List<String> errors) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(message, cap(errors)));
    }

    /** Keeps error bodies small even when a 50,000-element request is invalid everywhere. */
    static List<String> cap(List<String> errors) {
        if (errors.size() <= ErrorMessages.MAX_ERRORS) {
            return errors;
        }
        List<String> capped = new ArrayList<>(errors.subList(0, ErrorMessages.MAX_ERRORS));
        capped.add(String.format(ErrorMessages.MORE_ERRORS, errors.size() - ErrorMessages.MAX_ERRORS));
        return capped;
    }

    private static String parameterName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null && !requestParam.name().isEmpty()) {
            return requestParam.name();
        }
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null && !pathVariable.name().isEmpty()) {
            return pathVariable.name();
        }
        String name = parameter.getParameterName();
        return name != null ? name : "arg" + parameter.getParameterIndex();
    }

    /**
     * Property path without the method name: {@code generate.orders} becomes {@code orders},
     * {@code upsert.request.orders[0].latitude} becomes {@code request.orders[0].latitude}.
     */
    static String path(ConstraintViolation<?> violation) {
        StringBuilder path = new StringBuilder();
        for (Path.Node node : violation.getPropertyPath()) {
            ElementKind kind = node.getKind();
            if (kind == ElementKind.METHOD || kind == ElementKind.CONSTRUCTOR) {
                continue;
            }
            if (node.isInIterable()) {
                path.append('[').append(node.getIndex() != null ? node.getIndex() : node.getKey()).append(']');
            }
            if (kind != ElementKind.CONTAINER_ELEMENT && node.getName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(node.getName());
            }
        }
        return path.toString();
    }
}
