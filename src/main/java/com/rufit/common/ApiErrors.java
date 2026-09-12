package com.rufit.common;

import com.rufit.workout.WorkoutConflict;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import java.util.List;

@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {
    public static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", e.getMessage());
    }
    @ExceptionHandler(WorkoutConflict.class)
    ProblemDetail conflict(WorkoutConflict e) {
        return problem(HttpStatus.CONFLICT, e.code(), e.getMessage());
    }
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    ProblemDetail concurrentWrite(Exception e) {
        return problem(HttpStatus.CONFLICT, "STATE_CONFLICT", "다른 요청과 충돌했습니다. 최신 기록을 조회해 주세요.");
    }
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = problem(status, "VALIDATION_FAILED", "입력값을 확인해 주세요.");
        List<FieldIssue> fields = e.getBindingResult().getFieldErrors().stream()
            .map(error -> new FieldIssue(error.getField(), error.getCode() == null ? "Invalid" : error.getCode()))
            .distinct().toList();
        body.setProperty("errors", fields);
        return new ResponseEntity<>(body, headers, status);
    }
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return new ResponseEntity<>(problem(status, "INVALID_JSON", "요청 형식을 확인해 주세요."), headers, status);
    }
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return new ResponseEntity<>(problem(status, "INVALID_INPUT", "요청 값의 형식을 확인해 주세요."), headers, status);
    }
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String code = switch (status.value()) {
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "INVALID_REQUEST";
        };
        String detail = switch (status.value()) {
            case 404 -> "요청한 항목을 찾을 수 없습니다.";
            case 405 -> "지원하지 않는 요청 방식입니다.";
            case 415 -> "지원하지 않는 데이터 형식입니다.";
            default -> "요청을 처리할 수 없습니다.";
        };
        return super.handleExceptionInternal(e, problem(status, code, detail), headers, status, request);
    }
    public record FieldIssue(String field, String code) {}
}
