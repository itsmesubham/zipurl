package com.example.zipurl.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import com.example.zipurl.config.ZipurlProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

@Service
@ConditionalOnProperty(prefix = "zipurl.access-count", name = "mode", havingValue = "async", matchIfMissing = true)
public class AsyncDatabaseAccessCountService implements AccessCountService {

    private static final Logger log = LoggerFactory.getLogger(AsyncDatabaseAccessCountService.class);

    private static final String UPDATE_ACCESS_COUNT_SQL = """
            UPDATE short_urls
            SET access_count = access_count + :delta
            WHERE alias = :alias
              AND (expires_at IS NULL OR expires_at > now())
            """;

    private static final Duration SHUTDOWN_FLUSH_TIMEOUT = Duration.ofSeconds(5);

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ZipurlProperties zipurlProperties;
    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, LongAdder> pendingCounts = new ConcurrentHashMap<>();
    private final AtomicBoolean flushInProgress = new AtomicBoolean(false);
    private final AtomicLong droppedCounts = new AtomicLong();
    private final Counter flushSuccessCounter;
    private final Counter flushFailureCounter;
    private final Counter droppedCountCounter;
    private final Timer flushTimer;
    private final ExecutorService shutdownExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "zipurl-access-count-shutdown");
        thread.setDaemon(true);
        return thread;
    });

    public AsyncDatabaseAccessCountService(
        NamedParameterJdbcTemplate jdbcTemplate,
        ZipurlProperties zipurlProperties,
        MeterRegistry meterRegistry
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.zipurlProperties = zipurlProperties;
        this.meterRegistry = meterRegistry;
        this.flushSuccessCounter = Counter.builder("zipurl.access.count.flush.success")
                .description("Number of successfully flushed access-count batches")
                .register(meterRegistry);
        this.flushFailureCounter = Counter.builder("zipurl.access.count.flush.failure")
                .description("Number of failed access-count flush batches")
                .register(meterRegistry);
        this.droppedCountCounter = Counter.builder("zipurl.access.count.dropped")
                .description("Number of dropped access-count events")
                .register(meterRegistry);
        this.flushTimer = Timer.builder("zipurl.access.count.flush.duration")
                .description("Duration of access-count flushes")
                .register(meterRegistry);
        Gauge.builder("zipurl.access.count.pending.aliases", pendingCounts, ConcurrentHashMap::size)
                .description("Pending aliases waiting to be flushed")
                .register(meterRegistry);
        Gauge.builder("zipurl.access.count.pending.total", this, service -> (double) service.pendingCountTotal())
                .description("Pending access-count increments waiting to be flushed")
                .register(meterRegistry);
    }

    @Override
    public void recordAccess(String alias) {
        LongAdder counter = pendingCounts.get(alias);
        if (counter != null) {
            counter.increment();
            return;
        }

        if (pendingCounts.size() >= zipurlProperties.getAccessCountMaxPendingAliases()) {
            recordDroppedCounts(1, "pending counter map is full");
            return;
        }

        LongAdder newCounter = new LongAdder();
        LongAdder existingCounter = pendingCounts.putIfAbsent(alias, newCounter);
        if (existingCounter == null) {
            newCounter.increment();
            return;
        }

        existingCounter.increment();
    }

    @Scheduled(fixedDelayString = "${zipurl.access-count.flush-interval-ms:2000}")
    public void scheduledFlush() {
        flushPendingCounts(true);
    }

    public int flushPendingCountsForTests() {
        return flushPendingCounts(true);
    }

    @PreDestroy
    public void flushOnShutdown() {
        Future<Integer> future = shutdownExecutor.submit(() -> flushPendingCounts(true));
        try {
            Integer flushed = future.get(SHUTDOWN_FLUSH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            log.info("Flushed {} pending access count(s) during shutdown", flushed);
        } catch (Exception exception) {
            log.warn("Timed out flushing pending access counts during shutdown after {} ms",
                    SHUTDOWN_FLUSH_TIMEOUT.toMillis());
        } finally {
            shutdownExecutor.shutdownNow();
        }
    }

    private int flushPendingCounts(boolean shutdownFlush) {
        Timer.Sample sample = Timer.start(meterRegistry);
        if (!flushInProgress.compareAndSet(false, true)) {
            sample.stop(flushTimer);
            return 0;
        }

        try {
            Map<String, Long> drainedCounts = drainPendingCounts();
            if (drainedCounts.isEmpty()) {
                sample.stop(flushTimer);
                return 0;
            }

            int flushed = persistCounts(drainedCounts, shutdownFlush);
            sample.stop(flushTimer);
            return flushed;
        } finally {
            flushInProgress.set(false);
        }
    }

    private Map<String, Long> drainPendingCounts() {
        Map<String, Long> drainedCounts = new LinkedHashMap<>();
        pendingCounts.forEach((alias, counter) -> {
            long delta = counter.sumThenReset();
            if (delta > 0) {
                drainedCounts.put(alias, delta);
            }
        });
        return drainedCounts;
    }

    private int persistCounts(Map<String, Long> drainedCounts, boolean allowRequeue) {
        int batchSize = Math.max(1, Math.toIntExact(zipurlProperties.getAccessCountBatchSize()));
        List<Map<String, Long>> batches = partition(drainedCounts, batchSize);
        int flushed = 0;

        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            Map<String, Long> batch = batches.get(batchIndex);
            try {
                flushed += persistBatch(batch);
                cleanupDrainedEntries(batch.keySet());
                flushSuccessCounter.increment();
            } catch (DataAccessException exception) {
                flushFailureCounter.increment();
                Map<String, Long> remainingCounts = mergeRemainingBatches(batches, batchIndex);
                log.warn("Failed to flush {} pending access-count alias(es) in batch {} of {}: {}",
                        remainingCounts.size(), batchIndex + 1, batches.size(), exception.getMessage(), exception);
                if (allowRequeue) {
                    requeueCounts(remainingCounts);
                } else {
                    recordDroppedCounts(sumCounts(remainingCounts), "shutdown flush failure");
                }
                return flushed;
            }
        }

        if (flushed > 0) {
            log.info("Flushed {} access count(s) across {} alias(es)", flushed, drainedCounts.size());
        }

        return flushed;
    }

    private int persistBatch(Map<String, Long> batch) {
        SqlParameterSource[] parameters = batch.entrySet().stream()
                .map(entry -> new MapSqlParameterSource()
                        .addValue("alias", entry.getKey())
                        .addValue("delta", entry.getValue()))
                .toArray(SqlParameterSource[]::new);

        int[] updatedRows = jdbcTemplate.batchUpdate(UPDATE_ACCESS_COUNT_SQL, parameters);
        int flushed = 0;
        int dropped = 0;

        int index = 0;
        for (Map.Entry<String, Long> entry : batch.entrySet()) {
            int updated = index < updatedRows.length ? updatedRows[index] : 0;
            if (updated > 0) {
                flushed += entry.getValue().intValue();
            } else {
                dropped += entry.getValue().intValue();
                log.warn("Dropped {} access count(s) for alias '{}' because the row was missing or expired",
                        entry.getValue(), entry.getKey());
            }
            index++;
        }

        if (dropped > 0) {
            recordDroppedCounts(dropped, "row missing or expired");
        }

        return flushed;
    }

    private void cleanupDrainedEntries(Collection<String> aliases) {
        aliases.forEach(alias ->
                pendingCounts.computeIfPresent(alias, (key, counter) -> counter.sum() == 0 ? null : counter));
    }

    private void requeueCounts(Map<String, Long> drainedCounts) {
        drainedCounts.forEach((alias, delta) -> {
            LongAdder counter = pendingCounts.get(alias);
            if (counter != null) {
                counter.add(delta);
                return;
            }

            if (pendingCounts.size() >= zipurlProperties.getAccessCountMaxPendingAliases()) {
                recordDroppedCounts(delta, "pending counter map is full during requeue");
                return;
            }

            LongAdder newCounter = new LongAdder();
            newCounter.add(delta);
            LongAdder existingCounter = pendingCounts.putIfAbsent(alias, newCounter);
            if (existingCounter != null) {
                existingCounter.add(delta);
            }
        });
    }

    private void recordDroppedCounts(long dropped, String reason) {
        if (dropped <= 0) {
            return;
        }

        long totalDropped = droppedCounts.addAndGet(dropped);
        droppedCountCounter.increment(dropped);
        log.warn("Dropped {} access count(s) ({}) - total dropped so far: {}",
                dropped, reason, totalDropped);
    }

    private List<Map<String, Long>> partition(Map<String, Long> drainedCounts, int batchSize) {
        List<Map<String, Long>> batches = new ArrayList<>();
        Map<String, Long> currentBatch = new LinkedHashMap<>();

        for (Map.Entry<String, Long> entry : drainedCounts.entrySet()) {
            currentBatch.put(entry.getKey(), entry.getValue());
            if (currentBatch.size() >= batchSize) {
                batches.add(currentBatch);
                currentBatch = new LinkedHashMap<>();
            }
        }

        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }

        return batches;
    }

    private Map<String, Long> mergeRemainingBatches(List<Map<String, Long>> batches, int failedBatchIndex) {
        Map<String, Long> remainingCounts = new LinkedHashMap<>();
        for (int i = failedBatchIndex; i < batches.size(); i++) {
            remainingCounts.putAll(batches.get(i));
        }

        return remainingCounts;
    }

    private long sumCounts(Map<String, Long> counts) {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    private long pendingCountTotal() {
        return pendingCounts.values().stream().mapToLong(LongAdder::sum).sum();
    }
}
