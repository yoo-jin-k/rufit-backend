package com.rufit.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** JSON 파싱 전에 본문 크기/빈도를 제한합니다. 클라이언트가 준 프록시 IP는 신뢰하지 않습니다. */
final class AuthRequestFilter extends OncePerRequestFilter {

  private final AuthLimits limits;
  private final ObjectMapper mapper;

  AuthRequestFilter(AuthLimits limits, ObjectMapper mapper) {
    this.limits = limits;
    this.mapper = mapper;
  }

  @Override
  protected void doFilterInternal(
    HttpServletRequest request,
    HttpServletResponse response,
    FilterChain chain
  ) throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (!path.startsWith("/api/v1/auth/")) {
      chain.doFilter(request, response);
      return;
    }
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
    HttpServletRequest checked = request;
    try {
      if ("POST".equals(request.getMethod())) {
        if (!path.equals("/api/v1/auth/login-attempts")) limits.take(
          "auth-post:" + request.getRemoteAddr(),
          120,
          60
        );
        if (request.getContentLengthLong() > 2048) throw new AuthFailure(413, "PAYLOAD_TOO_LARGE");
        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity")) throw new AuthFailure(
          400,
          "INVALID_INPUT"
        );
        byte[] body = request.getInputStream().readNBytes(2049);
        if (body.length > 2048) throw new AuthFailure(413, "PAYLOAD_TOO_LARGE");
        checked = new Body(request, body);
      }
      if (
        request.getQueryString() != null && request.getQueryString().length() > 8192
      ) throw new AuthFailure(400, "INVALID_INPUT");
    } catch (AuthFailure failure) {
      AuthResponses.write(mapper, request, response, failure);
      return;
    } catch (DataAccessException | TransactionException failure) {
      AuthResponses.write(mapper, request, response, new AuthFailure(503, "STORAGE_UNAVAILABLE"));
      return;
    }
    chain.doFilter(checked, response);
  }

  private static class Body extends HttpServletRequestWrapper {

    private final byte[] bytes;

    Body(HttpServletRequest request, byte[] bytes) {
      super(request);
      this.bytes = bytes;
    }

    @Override
    public ServletInputStream getInputStream() {
      var input = new ByteArrayInputStream(bytes);
      return new ServletInputStream() {
        @Override
        public int read() {
          return input.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
          return input.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
          return input.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new IllegalStateException("Only synchronous JSON requests are supported");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() {
      return bytes.length;
    }

    @Override
    public long getContentLengthLong() {
      return bytes.length;
    }
  }
}
