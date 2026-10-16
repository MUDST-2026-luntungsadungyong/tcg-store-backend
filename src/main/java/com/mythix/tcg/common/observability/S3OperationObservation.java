package com.mythix.tcg.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/** Records S3 operation metrics with only finite operation and outcome labels. */
public final class S3OperationObservation {

    public enum Operation {
        UPLOAD("upload"),
        DELETE("delete");

        private final String metricTag;

        Operation(String metricTag) {
            this.metricTag = metricTag;
        }

        String metricTag() {
            return metricTag;
        }
    }

    private static final Duration[] BUCKETS = {
        Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250),
        Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(5)
    };

    private S3OperationObservation() {
    }

    public static void record(MeterRegistry registry, Operation operation, Runnable action) {
        long started = System.nanoTime();
        String outcome = "success";
        try {
            action.run();
        } catch (RuntimeException exception) {
            outcome = "error";
            throw exception;
        } finally {
            registry.counter("tcg.storage.operations", "operation", operation.metricTag(), "outcome", outcome)
                .increment();
            Timer.builder("tcg.storage.operation.duration")
                .description("Duration of S3 compatible object storage operations")
                .serviceLevelObjectives(BUCKETS)
                .tag("operation", operation.metricTag())
                .tag("outcome", outcome)
                .register(registry)
                .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }
}
