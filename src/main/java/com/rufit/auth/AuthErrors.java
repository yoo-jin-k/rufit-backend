package com.rufit.auth;

import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import com.rufit.common.ApiErrors;

@RestControllerAdvice(assignableTypes=AuthController.class)
@Profile("social")
@Order(-10)
public class AuthErrors {
    // 인증 DB 실패가 /error로 재전송되어 401로 바뀌거나 SQL 원문이 로그에 노출되지 않게 한다.
    @ExceptionHandler({org.springframework.dao.DataAccessException.class,org.springframework.transaction.TransactionException.class})
    ResponseEntity<ProblemDetail> storage(Exception error) {
        return failure(new AuthFailure(503,"STORAGE_UNAVAILABLE"));
    }
    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<ProblemDetail> failure(AuthFailure error) {
        HttpHeaders headers=new HttpHeaders(); headers.setCacheControl("no-store"); headers.set("Referrer-Policy","no-referrer");
        if(error.retryAfter()>0) headers.set("Retry-After",Long.toString(error.retryAfter()));
        if(error.status()==401) headers.set("WWW-Authenticate","Bearer realm=\"rufit\"");
        return new ResponseEntity<>(ApiErrors.problem(HttpStatusCode.valueOf(error.status()),error.code(),AuthResponses.detail(error.status())),headers,error.status());
    }
}
