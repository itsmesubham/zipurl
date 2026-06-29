package com.example.zipurl.service;

import java.time.Duration;
import java.util.LinkedHashMap;
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
    private final ConcurrentHashMap<String, LongAdder> pendingCounts = new ConcurrentHashMap<>();
    private final AtomicBoolean flushInProgress = new AtomicBoolean(false);
    private final AtomicLong droppedCounts = new AtomicLong();
    private final ExecutorService shutdownExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "zipurl-access-count-shutdown");
        thread.setDaemon(true);
        return thread;
    });

    public AsyncDatabaseAccessCountService(NamedParameterJdbcTemplate jdbcTemplate, ZipurlProperties zipurlProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.zipurlProperties = zipurlProperties;
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

    @Scheduled(fixedDelayString = "${zipurl.access-count.flush-interval-ms:1000}")
    public void scheduledFlush() {
        flushPendingCounts(true);
    }

    public int flushPendingCountsForTests() {
        return flushPendingCounts(true);
    }

    @PreDestroy
    public void flushOnShutdown() {
        Future<?> future = shutdownExecutor.submit(() -> flushPendingCounts(true));
        try {
            future.get(SHUTDOWN_FLUSH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            log.warn("Timed out flushing pending access counts during shutdown after {} ms",
                    SHUTDOWN_FLUSH_TIMEOUT.toMillis());
        } finally {
            shutdownExecutor.shutdownNow();
        }
    }

    private int flushPendingCounts(boolean shutdownFlush) {
        if (!flushInProgress.compareAndSet(false, true)) {
            return 0;
        }

        try {
            Map<String, Long> drainedCounts = drainPendingCounts();
            if (drainedCounts.isEmpty()) {
                return 0;
            }

            return persistCounts(drainedCounts, shutdownFlush);
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
        SqlParameterSource[] batch = drainedCounts.entrySet().stream()
                .map(entry -> new MapSqlParameterSource()
                        .addValue("alias", entry.getKey())
                        .addValue("delta", entry.getValue()))
                .toArray(SqlParameterSource[]::new);

        try {
            int[] updatedRows = jdbcTemplate.batchUpdate(UPDATE_ACCESS_COUNT_SQL, batch);
            int flushed = 0;
            int dropped = 0;

            int index = 0;
            for (Map.Entry<String, Long> entry : drainedCounts.entrySet()) {
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

            cleanupDrainedEntries(drainedCounts);
            log.info("Flushed {} access count(s) across {} alias(es)", flushed, drainedCounts.size());
            return flushed;
        } catch (DataAccessException exception) {
            log.warn("Failed to flush {} pending access-count alias(es): {}",
                    drainedCounts.size(), exception.getMessage(), exception);
            if (allowRequeue) {
                requeueCounts(drainedCounts);
            } else {
                recordDroppedCounts(drainedCounts.values().stream().mapToLong(Long::longValue).sum(),
                        "shutdown flush failure");
            }
            return 0;
        }
    }

    private void cleanupDrainedEntries(Map<String, Long> drainedCounts) {
        drainedCounts.keySet().forEach(alias ->
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
        log.warn("Dropped {} access count(s) ({}) - total dropped so far: {}",
                dropped, reason, totalDropped);
    }
}
