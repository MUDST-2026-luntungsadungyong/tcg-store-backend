package com.mythix.tcg;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GracefulShutdownIntegrationTests {

    @Test
    void inFlightRequestCompletesBeforeApplicationStops() throws Exception {
        BlockingRequestFilter blockingFilter = new BlockingRequestFilter();
        ConfigurableApplicationContext context = new SpringApplicationBuilder(TcgStoreBackendApplication.class)
            .profiles("test")
            .properties(
                "spring.main.banner-mode=off",
                "logging.level.root=ERROR"
            )
            .initializers(applicationContext -> applicationContext.getBeanFactory()
                .registerSingleton("blockingRequestFilter", blockingFilter))
            .run("--server.port=0");

        try (HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/health"))
                .header(BlockingRequestFilter.BLOCK_HEADER, "true")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

            CompletableFuture<HttpResponse<String>> response = client.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString()
            );
            assertThat(blockingFilter.awaitRequest()).isTrue();

            CompletableFuture<Void> shutdown = CompletableFuture.runAsync(context::close);
            assertThatThrownBy(() -> shutdown.get(250, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            blockingFilter.release();

            assertThat(response.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            shutdown.get(10, TimeUnit.SECONDS);
        } finally {
            blockingFilter.release();
            if (context.isActive()) {
                context.close();
            }
        }
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class BlockingRequestFilter extends OncePerRequestFilter {

        static final String BLOCK_HEADER = "X-Test-Block-Request";

        private final CountDownLatch requestStarted = new CountDownLatch(1);
        private final CountDownLatch requestRelease = new CountDownLatch(1);

        @Override
        protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
        ) throws ServletException, IOException {
            if ("true".equals(request.getHeader(BLOCK_HEADER))) {
                requestStarted.countDown();
                try {
                    if (!requestRelease.await(10, TimeUnit.SECONDS)) {
                        throw new ServletException("Timed out waiting to release the test request");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new ServletException("Interrupted while waiting to release the test request", exception);
                }
            }
            filterChain.doFilter(request, response);
        }

        boolean awaitRequest() throws InterruptedException {
            return requestStarted.await(10, TimeUnit.SECONDS);
        }

        void release() {
            requestRelease.countDown();
        }
    }
}
