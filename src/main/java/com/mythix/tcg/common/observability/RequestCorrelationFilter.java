package com.mythix.tcg.common.observability;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Adds safe request correlation and emits a URL-free access event after completion. */
public final class RequestCorrelationFilter implements Filter {

    static final String REQUEST_ID_HEADER = "X-Request-ID";
    static final String REQUEST_ID_ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".requestId";
    private static final String START_ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".startNanos";
    private static final String LOGGED_ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".logged";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
            || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        String requestId = requestId(httpRequest);
        httpRequest.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        httpResponse.setHeader(REQUEST_ID_HEADER, requestId);
        if (httpRequest.getAttribute(START_ATTRIBUTE) == null) {
            httpRequest.setAttribute(START_ATTRIBUTE, System.nanoTime());
        }

        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        MDC.put("requestId", requestId);
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error exception) {
            failed = true;
            throw exception;
        } finally {
            try {
                if (httpRequest.isAsyncStarted()) {
                    addAsyncListener(httpRequest, httpResponse);
                } else if (!failed || httpRequest.getDispatcherType() == DispatcherType.ERROR) {
                    writeAccessEvent(httpRequest, httpResponse, failed);
                }
            } finally {
                restoreMdc(previousMdc);
            }
        }
    }

    private static void addAsyncListener(HttpServletRequest request, HttpServletResponse response) {
        try {
            request.getAsyncContext().addListener(new CompletionListener(request, response), request, response);
        } catch (IllegalStateException ignored) {
            // Completion raced listener registration; emit once using the response's current final status.
            writeAccessEvent(request, response, false);
        }
    }

    private static void writeAccessEvent(HttpServletRequest request, HttpServletResponse response, boolean failed) {
        AtomicBoolean logged = (AtomicBoolean) request.getAttribute(LOGGED_ATTRIBUTE);
        if (logged == null) {
            synchronized (request) {
                logged = (AtomicBoolean) request.getAttribute(LOGGED_ATTRIBUTE);
                if (logged == null) {
                    logged = new AtomicBoolean();
                    request.setAttribute(LOGGED_ATTRIBUTE, logged);
                }
            }
        }
        if (!logged.compareAndSet(false, true)) {
            return;
        }

        String requestId = (String) request.getAttribute(REQUEST_ID_ATTRIBUTE);
        String method = safeMethod(request.getMethod());
        String route = routeTemplate(request);
        long started = (long) request.getAttribute(START_ATTRIBUTE);
        long durationMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
        int status = failed && response.getStatus() < 500 ? 500 : response.getStatus();
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        try {
            MDC.put("requestId", requestId);
            MDC.put("route", route);
            MDC.put("method", method);
            MDC.put("status", Integer.toString(status));
            MDC.put("durationMs", Long.toString(durationMs));
            log.info("http_access");
        } finally {
            restoreMdc(previousMdc);
        }
    }

    private static String requestId(HttpServletRequest request) {
        Object existing = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        if (existing instanceof String value) {
            return value;
        }
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        return supplied != null && SAFE_REQUEST_ID.matcher(supplied).matches()
            ? supplied
            : UUID.randomUUID().toString();
    }

    private static String routeTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String template && template.startsWith("/") && template.length() <= 200) {
            return template;
        }
        return "UNKNOWN";
    }

    private static String safeMethod(String method) {
        return method != null && method.matches("[A-Z]{1,12}") ? method : "UNKNOWN";
    }

    private static void restoreMdc(Map<String, String> previous) {
        if (previous == null || previous.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }

    private static final class CompletionListener implements AsyncListener {
        private final HttpServletRequest request;
        private final HttpServletResponse response;

        private CompletionListener(HttpServletRequest request, HttpServletResponse response) {
            this.request = request;
            this.response = response;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            writeAccessEvent(request, response, false);
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            writeAccessEvent(request, response, true);
        }

        @Override
        public void onError(AsyncEvent event) {
            writeAccessEvent(request, response, true);
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            try {
                event.getAsyncContext().addListener(this, request, response);
            } catch (IllegalStateException ignored) {
                writeAccessEvent(request, response, false);
            }
        }
    }
}
