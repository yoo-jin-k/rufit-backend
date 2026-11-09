package com.rufit.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** 자체 JWT 인증 전용. 업무 소유권은 요청 값이 아니라 검증된 DB의 owner 키로 정합니다. */
final class SessionAuthenticationFilter extends OncePerRequestFilter {

  private final AuthSessions sessions;
  private final ObjectMapper mapper;

  SessionAuthenticationFilter(AuthSessions sessions, ObjectMapper mapper) {
    this.sessions = sessions;
    this.mapper = mapper;
  }

  @Override
  protected void doFilterInternal(
    HttpServletRequest request,
    HttpServletResponse response,
    FilterChain chain
  ) throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/account/")) {
      response.setHeader("Cache-Control", "no-store");
      response.setHeader("Referrer-Policy", "no-referrer");
    }
    if (!path.startsWith("/api/v1/auth/")) {
      String authorization = request.getHeader("Authorization");
      if (authorization != null) {
        try {
          if (
            Collections.list(request.getHeaders("Authorization")).size() != 1 ||
            !authorization.startsWith("Bearer ")
          ) throw AuthFailure.proof();
          String owner = sessions.authenticate(authorization.substring(7));
          var context = SecurityContextHolder.createEmptyContext();
          context.setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(owner, null, List.of())
          );
          SecurityContextHolder.setContext(context);
        } catch (AuthFailure error) {
          AuthResponses.write(mapper, request, response, error);
          return;
        } catch (DataAccessException | TransactionException error) {
          AuthResponses.write(
            mapper,
            request,
            response,
            new AuthFailure(503, "STORAGE_UNAVAILABLE")
          );
          return;
        }
      }
    }
    chain.doFilter(request, response);
  }
}
