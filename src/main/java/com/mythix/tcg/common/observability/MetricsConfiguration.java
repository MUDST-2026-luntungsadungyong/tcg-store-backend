package com.mythix.tcg.common.observability;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumSet;

@Configuration(proxyBeanMethods = false)
public class MetricsConfiguration {

    @Bean
    FilterRegistrationBean<RequestCorrelationFilter> requestCorrelationFilter() {
        FilterRegistrationBean<RequestCorrelationFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestCorrelationFilter());
        registration.setName("requestCorrelationFilter");
        registration.setOrder(Integer.MIN_VALUE);
        registration.setAsyncSupported(true);
        registration.setDispatcherTypes(EnumSet.of(
            DispatcherType.REQUEST,
            DispatcherType.ASYNC,
            DispatcherType.ERROR
        ));
        return registration;
    }
}
