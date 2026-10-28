package com.rufit.auth;

import jakarta.servlet.http.*;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;

/** 보안 필터와 컨트롤러가 같은 오류 계약을 쓰고 비밀/외부 예외를 출력하지 않게 한다. */
final class AuthResponses {
    private AuthResponses() {}
    static void write(ObjectMapper mapper,HttpServletRequest request,HttpServletResponse response,AuthFailure error) throws IOException {
        response.setStatus(error.status());
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control","no-store");
        response.setHeader("Referrer-Policy","no-referrer");
        if(error.status()==401) response.setHeader("WWW-Authenticate","Bearer realm=\"rufit\"");
        if(error.retryAfter()>0) response.setHeader("Retry-After",Long.toString(error.retryAfter()));
        mapper.writeValue(response.getOutputStream(),Map.of("type","about:blank","title",title(error.status()),"status",error.status(),
            "code",error.code(),"detail",detail(error.status()),"instance",request.getRequestURI()));
    }
    static String title(int status) {
        return switch(status) { case 400->"Bad Request";case 401->"Unauthorized";case 409->"Conflict";
            case 413->"Payload Too Large";case 429->"Too Many Requests";default->"Service Unavailable"; };
    }
    static String detail(int status) {
        return switch(status) {case 400,413->"요청 형식을 확인해 주세요.";case 401->"다시 로그인해 주세요.";
            case 409->"이미 처리 중이거나 완료된 로그인 시도입니다.";case 429->"잠시 후 다시 시도해 주세요.";
            default->"로그인 서비스에 일시적으로 연결할 수 없습니다.";};
    }
}
