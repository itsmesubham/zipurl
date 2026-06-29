package com.example.zipurl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.Semaphore;

import com.example.zipurl.config.ZipurlProperties;
import com.example.zipurl.dto.CreateShortUrlRequest;
import com.example.zipurl.exception.AliasAlreadyExistsException;
import com.example.zipurl.model.ShortUrl;
import com.example.zipurl.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

@SpringBootTest(properties = {
        "zipurl.url-cache.mode=valkey",
        "zipurl.valkey.enabled=true",
        "zipurl.access-count.mode=disabled"
})
class ValkeyRedirectCacheWriteThroughTests {

    @Autowired
    private UrlShorteningService urlShorteningService;

    @Autowired
    private UrlCacheService urlCacheService;

    @Autowired
    private ShortUrlRepository shortUrlRepository;

    @SpyBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private StringRedisTemplate redisTemplate;

    private ValueOperations<String, String> valueOperations;

    @Autowired
    private ZipurlProperties zipurlProperties;

    @BeforeEach
    void setUp() {
        shortUrlRepository.deleteAll();
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        clearInvocations(redisTemplate, valueOperations, jdbcTemplate);
    }

    @Test
    void createWritesThroughToValkeyAndLocalCacheAfterCommit() {
        ShortUrl shortUrl = urlShorteningService.createShortUrl(
                new CreateShortUrlRequest("https://example.com/write-through", "write001", null)
        );

        assertThat(shortUrl.getAlias()).isEqualTo("write001");
        verify(valueOperations).set(eq("zipurl:url:write001"), any(String.class), any(Duration.class));

        clearInvocations(redisTemplate, valueOperations, jdbcTemplate);

        assertThat(urlShorteningService.resolveOriginalUrl("write001"))
                .isEqualTo("https://example.com/write-through");

        verifyNoInteractions(redisTemplate, valueOperations, jdbcTemplate);
    }

    @Test
    void createDoesNotCacheWhenTransactionFails() {
        shortUrlRepository.saveAndFlush(new ShortUrl("dup001", "https://example.com/existing"));

        assertThatThrownBy(() -> urlShorteningService.createShortUrl(
                new CreateShortUrlRequest("https://example.com/duplicate", "dup001", null)
        )).isInstanceOf(AliasAlreadyExistsException.class);

        verifyNoInteractions(valueOperations);
    }

    @Test
    void redirectValkeyHitPopulatesCaffeineAndSkipsDatabase() {
        when(valueOperations.get("zipurl:url:l2hit001"))
                .thenReturn("{\"originalUrl\":\"https://example.com/l2\",\"expiresAt\":null}");

        urlCacheService.invalidate("l2hit001");
        clearInvocations(redisTemplate, valueOperations, jdbcTemplate);

        assertThat(urlShorteningService.resolveOriginalUrl("l2hit001"))
                .isEqualTo("https://example.com/l2");

        verify(valueOperations).get("zipurl:url:l2hit001");
        verifyNoInteractions(jdbcTemplate);

        clearInvocations(redisTemplate, valueOperations, jdbcTemplate);

        assertThat(urlShorteningService.resolveOriginalUrl("l2hit001"))
                .isEqualTo("https://example.com/l2");

        verifyNoInteractions(redisTemplate, valueOperations, jdbcTemplate);
    }

    @Test
    void createLimiterReturns429WhenSaturated() throws Exception {
        Field semaphoreField = UrlShorteningService.class.getDeclaredField("createSemaphore");
        semaphoreField.setAccessible(true);
        Semaphore semaphore = (Semaphore) semaphoreField.get(urlShorteningService);
        semaphore.acquire(zipurlProperties.getCreateMaxConcurrent());

        try {
            ResponseStatusException exception = catchThrowableOfType(() -> urlShorteningService.createShortUrl(
                    new CreateShortUrlRequest("https://example.com/limit", null, null)
            ), ResponseStatusException.class);

            assertThat(exception).isNotNull();
            assertThat(exception.getStatusCode().value()).isEqualTo(429);
        } finally {
            semaphore.release(zipurlProperties.getCreateMaxConcurrent());
        }
    }
}
