package com.mythix.tcg.common.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.AsyncEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestCorrelationFilterTests {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestCorrelationFilter.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();

    @AfterEach
    void cleanMdc() {
        MDC.clear();
        logger.detachAppender(events);
    }

    @Test
    void acceptsSafeIdsAndEmitsOnlyTemplatedAccessFields() throws Exception {
        attachAppender();
        MockHttpServletRequest request = request("req-ABC_123.x");
        request.setRequestURI("/api/orders/customer-secret");
        request.setQueryString("access_token=query-secret");
        request.addHeader("Authorization", "Bearer header-secret");
        request.setContent("body-secret".getBytes());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (incoming, outgoing) -> {
            assertThat(MDC.get("requestId")).isEqualTo("req-ABC_123.x");
            ((MockHttpServletResponse) outgoing).setStatus(201);
        });

        assertThat(response.getHeader("X-Request-ID")).isEqualTo("req-ABC_123.x");
        assertThat(MDC.get("requestId")).isNull();
        assertThat(events.list).hasSize(1);
        ILoggingEvent event = events.list.getFirst();
        assertThat(event.getFormattedMessage()).isEqualTo("http_access");
        assertThat(event.getMDCPropertyMap())
            .containsEntry("requestId", "req-ABC_123.x")
            .containsEntry("route", "/api/orders/{orderId}")
            .containsEntry("method", "GET")
            .containsEntry("status", "201")
            .containsKey("durationMs");
        assertThat(event.getFormattedMessage()).doesNotContain(
            "customer-secret", "access_token", "query-secret", "header-secret", "body-secret"
        );
        assertThat(event.getMDCPropertyMap().toString()).doesNotContain(
            "customer-secret", "query-secret", "header-secret", "body-secret"
        );
    }

    @Test
    void replacesMalformedAndOversizedIdsAndUsesUnknownRouteForUnmappedRequest() throws Exception {
        attachAppender();
        for (String invalid : List.of("bad\nforged", "id with spaces", "x".repeat(65))) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockHttpServletRequest request = request(invalid);
            request.removeAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            filter.doFilter(request, response, (incoming, outgoing) -> { });
            assertThat(response.getHeader("X-Request-ID")).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
            );
        }
        assertThat(events.list).hasSize(3);
        assertThat(events.list).allSatisfy(event ->
            assertThat(event.getMDCPropertyMap()).containsEntry("route", "UNKNOWN"));
    }

    @Test
    void doesNotLogAnAsyncRequestBeforeCompletionAndLogsErrorAsFailure() throws Exception {
        attachAppender();
        MockHttpServletRequest request = request("async-1");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> request.startAsync());

        assertThat(events.list).isEmpty();
        assertThat(MDC.get("requestId")).isNull();
        MockAsyncContext asyncContext = (MockAsyncContext) request.getAsyncContext();
        assertThat(asyncContext.getListeners()).hasSize(1);
        asyncContext.getListeners().getFirst().onError(new AsyncEvent(asyncContext, request, response));

        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getMDCPropertyMap())
            .containsEntry("requestId", "async-1")
            .containsEntry("status", "500");
        assertThat(MDC.get("requestId")).isNull();
        asyncContext.getListeners().getFirst().onComplete(new AsyncEvent(asyncContext, request, response));
        assertThat(events.list).hasSize(1);
    }

    @Test
    void logsOnlyAfterErrorDispatchAndClearsCorrelationBeforeTheNextRequest() throws Exception {
        attachAppender();
        MockHttpServletRequest failedRequest = request("error-request");
        MockHttpServletResponse failedResponse = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(failedRequest, failedResponse, (incoming, outgoing) -> {
            throw new jakarta.servlet.ServletException("private exception text");
        })).isInstanceOf(jakarta.servlet.ServletException.class);

        assertThat(events.list).isEmpty();
        failedRequest.setDispatcherType(jakarta.servlet.DispatcherType.ERROR);
        failedResponse.setStatus(500);
        filter.doFilter(failedRequest, failedResponse, (incoming, outgoing) -> { });

        assertThat(events.list).hasSize(1);
        assertThat(events.list.getFirst().getMDCPropertyMap())
            .containsEntry("requestId", "error-request")
            .containsEntry("status", "500");
        assertThat(MDC.get("requestId")).isNull();

        MockHttpServletResponse nextResponse = new MockHttpServletResponse();
        filter.doFilter(request("next-request"), nextResponse, (incoming, outgoing) -> {
            assertThat(MDC.get("requestId")).isEqualTo("next-request");
        });
        assertThat(events.list).hasSize(2);
        assertThat(events.list.getLast().getMDCPropertyMap()).containsEntry("requestId", "next-request");
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void restoresPreexistingMdcAndDoesNotLogSynchronousExceptionsAsSuccess() {
        attachAppender();
        MDC.put("existing", "kept");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request("sync-error"), response, (incoming, outgoing) -> {
            throw new jakarta.servlet.ServletException("private exception text");
        })).isInstanceOf(jakarta.servlet.ServletException.class);

        assertThat(MDC.get("existing")).isEqualTo("kept");
        assertThat(MDC.get("requestId")).isNull();
        assertThat(events.list).isEmpty();
    }

    private void attachAppender() {
        events.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
        events.start();
        logger.addAppender(events);
    }

    private static MockHttpServletRequest request(String requestId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/customer-secret");
        request.addHeader("X-Request-ID", requestId);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/orders/{orderId}");
        return request;
    }
}
