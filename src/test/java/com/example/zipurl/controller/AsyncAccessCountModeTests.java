package com.example.zipurl.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import com.example.zipurl.model.ShortUrl;
import com.example.zipurl.repository.ShortUrlRepository;
import com.example.zipurl.service.AsyncDatabaseAccessCountService;
import com.example.zipurl.service.CachedRedirectTarget;
import com.example.zipurl.service.UrlCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "zipurl.access-count.mode=async",
        "zipurl.url-cache.mode=local",
        "zipurl.access-count.flush-interval-ms=60000"
})
@AutoConfigureMockMvc
class AsyncAccessCountModeTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShortUrlRepository shortUrlRepository;

    @Autowired
    private AsyncDatabaseAccessCountService asyncDatabaseAccessCountService;

    @Autowired
    private UrlCacheService urlCacheService;

    @BeforeEach
    void setUp() {
        shortUrlRepository.deleteAll();
    }

    @Test
    void redirectReturnsBeforePersistedCountUpdatesInAsyncMode() throws Exception {
        shortUrlRepository.saveAndFlush(new ShortUrl("async001", "https://example.com/async"));

        mockMvc.perform(get("/async001"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/async"));

        assertThat(shortUrlRepository.findByAlias("async001"))
                .hasValueSatisfying(shortUrl -> assertThat(shortUrl.getAccessCount()).isZero());

        asyncDatabaseAccessCountService.flushPendingCountsForTests();

        assertThat(shortUrlRepository.findByAlias("async001"))
                .hasValueSatisfying(shortUrl -> assertThat(shortUrl.getAccessCount()).isEqualTo(1));
    }

    @Test
    void expiredCachedAliasDoesNotRedirectOrCount() throws Exception {
        shortUrlRepository.saveAndFlush(
                new ShortUrl("async-expired", "https://example.com/expired", Instant.now().minusSeconds(1))
        );
        urlCacheService.putResolvedUrl(
                "async-expired",
                new CachedRedirectTarget("https://example.com/expired", Instant.now().minusSeconds(1))
        );

        mockMvc.perform(get("/async-expired"))
                .andExpect(status().isNotFound());

        assertThat(shortUrlRepository.findByAlias("async-expired"))
                .hasValueSatisfying(shortUrl -> assertThat(shortUrl.getAccessCount()).isZero());
    }

    @Test
    void metadataReadsPersistedCountAfterAsyncFlush() throws Exception {
        shortUrlRepository.saveAndFlush(new ShortUrl("async-meta", "https://example.com/meta"));

        mockMvc.perform(get("/async-meta"))
                .andExpect(status().isFound());

        asyncDatabaseAccessCountService.flushPendingCountsForTests();

        mockMvc.perform(get("/api/urls/async-meta"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.accessCount").value(1));
    }
}
