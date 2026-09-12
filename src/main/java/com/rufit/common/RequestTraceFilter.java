package com.rufit.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.web.filter.OncePerRequestFilter;

public class RequestTraceFilter extends OncePerRequestFilter {
    public static final String HEADER="X-Request-Id";
    private static final String ATTRIBUTE=RequestTraceFilter.class.getName()+".id";
    private static final String LOGGED=RequestTraceFilter.class.getName()+".logged";
    private static final Logger log=LoggerFactory.getLogger(RequestTraceFilter.class);
    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected boolean shouldNotFilterErrorDispatch() { return false; }
    @Override protected void doFilterNestedErrorDispatch(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        doFilterInternal(request,response,chain);
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        Object stored=request.getAttribute(ATTRIBUTE);
        String id=stored instanceof String value ? value : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE,id);response.setHeader(HEADER,id);
        String previous=MDC.get("requestId");MDC.put("requestId",id);
        boolean failed=false;
        try { chain.doFilter(request,response); }
        catch(ServletException | IOException | RuntimeException error) { failed=true;throw error; }
        finally {
            if(!request.isAsyncStarted() && request.getAttribute(LOGGED)==null) {
                request.setAttribute(LOGGED,Boolean.TRUE);
                if(!request.getRequestURI().equals("/actuator/health"))
                    log.info("request completed method={} status={}",request.getMethod(),failed?500:response.getStatus());
            }
            if(previous==null) MDC.remove("requestId");else MDC.put("requestId",previous);
        }
    }
}
