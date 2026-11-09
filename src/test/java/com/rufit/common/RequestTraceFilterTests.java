package com.rufit.common;

import static org.assertj.core.api.Assertions.*;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.*;

class RequestTraceFilterTests {

  @Test
  void createsItsOwnIdAndClearsThreadContextOnFailure() {
    var filter = new RequestTraceFilter();
    var request = new MockHttpServletRequest("GET", "/api/v1/workouts");
    var response = new MockHttpServletResponse();
    request.addHeader(RequestTraceFilter.HEADER, "client-supplied-value");
    assertThatThrownBy(() ->
      filter.doFilter(request, response, (req, res) -> {
        assertThat(MDC.get("requestId")).isEqualTo(response.getHeader(RequestTraceFilter.HEADER));
        throw new ServletException("test failure");
      })
    ).isInstanceOf(ServletException.class);
    assertThatCode(() ->
      UUID.fromString(response.getHeader(RequestTraceFilter.HEADER))
    ).doesNotThrowAnyException();
    assertThat(MDC.get("requestId")).isNull();
  }

  @Test
  void nestedErrorDispatchRestoresHeaderAfterResponseReset() throws Exception {
    var filter = new RequestTraceFilter();
    var request = new MockHttpServletRequest("GET", "/api/v1/workouts");
    var response = new MockHttpServletResponse();
    filter.doFilter(request, response, (req, res) -> {
      String original = response.getHeader(RequestTraceFilter.HEADER);
      request.setDispatcherType(DispatcherType.ERROR);
      request.setAttribute("jakarta.servlet.error.request_uri", "/api/v1/workouts");
      response.reset();
      filter.doFilter(request, response, (nestedReq, nestedRes) -> {
        assertThat(response.getHeader(RequestTraceFilter.HEADER)).isEqualTo(original);
        assertThat(MDC.get("requestId")).isEqualTo(original);
      });
    });
    assertThat(MDC.get("requestId")).isNull();
  }

  @Test
  void errorRedispatchKeepsRequestIdentityAndRestoresPreviousContext() throws Exception {
    var filter = new RequestTraceFilter();
    var request = new MockHttpServletRequest("GET", "/api/v1/workouts");
    var response = new MockHttpServletResponse();
    MDC.put("requestId", "outer-context");
    try {
      filter.doFilter(request, response, (req, res) -> {});
      String original = response.getHeader(RequestTraceFilter.HEADER);
      assertThat(MDC.get("requestId")).isEqualTo("outer-context");
      request.setDispatcherType(DispatcherType.ERROR);
      request.setAttribute("jakarta.servlet.error.request_uri", "/api/v1/workouts");
      response.reset();
      filter.doFilter(request, response, (req, res) ->
        assertThat(MDC.get("requestId")).isEqualTo(original)
      );
      assertThat(response.getHeader(RequestTraceFilter.HEADER)).isEqualTo(original);
      assertThat(MDC.get("requestId")).isEqualTo("outer-context");
    } finally {
      MDC.remove("requestId");
    }
  }
}
