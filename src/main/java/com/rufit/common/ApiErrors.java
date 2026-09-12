package com.rufit.common;
import com.rufit.workout.WorkoutConflict;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException e) { return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()); }
    @ExceptionHandler(WorkoutConflict.class)
    ProblemDetail conflict(WorkoutConflict e) { return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage()); }
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    ProblemDetail concurrentWrite(Exception e) { return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "다른 요청과 충돌했습니다. 최신 기록을 조회해 주세요."); }
}
