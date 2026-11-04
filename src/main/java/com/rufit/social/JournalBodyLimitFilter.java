package com.rufit.social;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** JSON 사진도 길이 헤더 유무와 관계없이 읽기 전에 상한을 적용합니다. */
@Component
public class JournalBodyLimitFilter extends OncePerRequestFilter {

  private static final int LIMIT = 14_100_000;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return (
      !request.getMethod().equals("PUT") ||
      !request.getRequestURI().startsWith("/api/v1/social/posts/mine/")
    );
  }

  @Override
  protected void doFilterInternal(
    HttpServletRequest request,
    HttpServletResponse response,
    FilterChain chain
  ) throws IOException, ServletException {
    byte[] data =
      request.getContentLengthLong() > LIMIT
        ? null
        : request.getInputStream().readNBytes(LIMIT + 1);
    if (data == null || data.length > LIMIT) {
      response.setStatus(413);
      response.setContentType("application/problem+json");
      response.setCharacterEncoding("UTF-8");
      response
        .getWriter()
        .write(
          "{\"status\":413,\"title\":\"사진 용량 초과\",\"detail\":\"사진 크기를 줄여 다시 저장해 주세요.\"}"
        );
      return;
    }
    chain.doFilter(
      new HttpServletRequestWrapper(request) {
        @Override
        public ServletInputStream getInputStream() {
          var input = new ByteArrayInputStream(data);
          return new ServletInputStream() {
            public int read() {
              return input.read();
            }

            public int read(byte[] b, int off, int len) {
              return input.read(b, off, len);
            }

            public boolean isFinished() {
              return input.available() == 0;
            }

            public boolean isReady() {
              return true;
            }

            public void setReadListener(ReadListener listener) {
              throw new UnsupportedOperationException();
            }
          };
        }

        @Override
        public BufferedReader getReader() {
          return new BufferedReader(
            new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)
          );
        }
      },
      response
    );
  }
}
