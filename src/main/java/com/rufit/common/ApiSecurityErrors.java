package com.rufit.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApiSecurityErrors implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper mapper;
    public ApiSecurityErrors(ObjectMapper mapper) { this.mapper = mapper; }
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException {
        response.setHeader("WWW-Authenticate", "Basic realm=\"rufit\"");
        write(request, response, 401, "Unauthorized", "AUTHENTICATION_REQUIRED", "인증이 필요합니다.");
    }
    public void bearer(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer realm=\"rufit\"");
        write(request, response, 401, "Unauthorized", "AUTHENTICATION_REQUIRED", "유효한 로그인 인증이 필요합니다.");
    }
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception) throws IOException {
        forbidden(request, response);
    }
    public void forbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(request, response, 403, "Forbidden", "ACCESS_DENIED", "요청 권한 또는 보안 토큰을 확인해 주세요.");
    }
    private void write(HttpServletRequest request, HttpServletResponse response, int status, String title, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        mapper.writeValue(response.getOutputStream(), Map.of("type", "about:blank", "title", title, "status", status,
            "code", code, "detail", detail, "instance", request.getRequestURI()));
    }
}
