package internal.api;

import internal.storage.Repository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Configuration
@RestController
public class MetricsConfig {

    private final PrometheusMeterRegistry meterRegistry;
    private final Timer editLatencyTimer;
    private final Counter transformConflictsCounter;
    private final Counter validationFailuresCounter;

    public MetricsConfig(PrometheusMeterRegistry meterRegistry, Repository repository) {
        this.meterRegistry = meterRegistry;

        this.editLatencyTimer = Timer.builder("edit_latency_ms")
                .description("OT processing latency")
                .publishPercentileHistogram()
                .register(meterRegistry);

        this.transformConflictsCounter = Counter.builder("transform_conflicts_total")
                .description("Number of concurrent operations that required transformation")
                .register(meterRegistry);

        this.validationFailuresCounter = Counter.builder("validation_failures_total")
                .description("Total number of failed validation attempts")
                .register(meterRegistry);

        Gauge.builder("snapshot_count", repository, Repository::countTotalSnapshots)
                .description("Total number of snapshots created")
                .register(meterRegistry);
    }

    public Timer getEditLatencyTimer() {
        return editLatencyTimer;
    }

    public Counter getTransformConflictsCounter() {
        return transformConflictsCounter;
    }

    public Counter getValidationFailuresCounter() {
        return validationFailuresCounter;
    }

    @GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getMetrics() {
        String scrape = meterRegistry.scrape();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, TextFormat.CONTENT_TYPE_004);
        return new ResponseEntity<>(scrape, headers, HttpStatus.OK);
    }
}
