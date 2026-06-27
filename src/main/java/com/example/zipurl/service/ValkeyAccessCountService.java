package com.example.zipurl.service;

import com.example.zipurl.repository.ShortUrlRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@ConditionalOnProperty(prefix = "zipurl.access-count", name = "mode", havingValue = "valkey")
public class ValkeyAccessCountService implements AccessCountService {

    private static final String DIRTY_ALIASES_KEY = "zipurl:access:dirty";
    private static final String ACCESS_COUNT_KEY_PREFIX = "zipurl:access:";

    private final StringRedisTemplate redisTemplate;
    private final ShortUrlRepository shortUrlRepository;
    private final TransactionTemplate transactionTemplate;
    private final int flushBatchSize;

    public ValkeyAccessCountService(
            StringRedisTemplate redisTemplate,
            ShortUrlRepository shortUrlRepository,
            PlatformTransactionManager transactionManager,
            @Value("${zipurl.access-count.flush-batch-size:1000}") int flushBatchSize
    ) {
        this.redisTemplate = redisTemplate;
        this.shortUrlRepository = shortUrlRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.flushBatchSize = flushBatchSize;
    }

    @Override
    public void recordAccess(String alias) {
        redisTemplate.opsForValue().increment(accessCountKey(alias));
        redisTemplate.opsForSet().add(DIRTY_ALIASES_KEY, alias);
    }

    @Override
    public long pendingAccessCount(String alias) {
        String count = redisTemplate.opsForValue().get(accessCountKey(alias));
        if (count == null) {
            return 0;
        }

        return Long.parseLong(count);
    }

    @Scheduled(
            fixedDelayString = "${zipurl.access-count.flush-interval-ms:5000}",
            initialDelayString = "${zipurl.access-count.flush-interval-ms:5000}"
    )
    public void flushPendingAccessCounts() {
        for (int index = 0; index < flushBatchSize; index++) {
            String alias = redisTemplate.opsForSet().pop(DIRTY_ALIASES_KEY);
            if (alias == null) {
                return;
            }

            flushAlias(alias);
        }
    }

    private void flushAlias(String alias) {
        String count = redisTemplate.opsForValue().getAndDelete(accessCountKey(alias));
        if (count == null) {
            return;
        }

        long delta = Long.parseLong(count);
        if (delta <= 0) {
            return;
        }

        transactionTemplate.executeWithoutResult(status ->
                shortUrlRepository.addAccessCountByAlias(alias, delta)
        );
    }

    private String accessCountKey(String alias) {
        return ACCESS_COUNT_KEY_PREFIX + alias;
    }
}
