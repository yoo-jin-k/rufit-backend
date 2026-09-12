package com.rufit.config;

import com.rufit.common.RequestTraceFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;

@Configuration
public class RequestTraceConfig {
    @Bean RequestTraceFilter requestTraceFilter() { return new RequestTraceFilter(); }
    @Bean FilterRegistrationBean<RequestTraceFilter> requestTraceRegistration(RequestTraceFilter filter) {
        var registration=new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE+20);
        registration.setDispatcherTypes(DispatcherType.REQUEST,DispatcherType.ASYNC,DispatcherType.ERROR);
        registration.setAsyncSupported(true);
        return registration;
    }
}
