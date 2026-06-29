package com.example.zipurl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;

import com.example.zipurl.config.ZipurlProperties;
import com.example.zipurl.repository.ShortUrlRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class UrlShorteningServiceHotPathTests {

    @Test
    void resolveOriginalUrlFromLocalCacheDoesNotTouchPersistenceOrTransactions() {
        AliasGenerator aliasGenerator = mock(AliasGenerator.class);
        AccessCountService accessCountService = mock(AccessCountService.class);
        ShortUrlRepository shortUrlRepository = mock(ShortUrlRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        ZipurlProperties zipurlProperties = new ZipurlProperties();
        LocalUrlCacheService urlCacheService = new LocalUrlCacheService(zipurlProperties);
        UrlShorteningService urlShorteningService = new UrlShorteningService(
                aliasGenerator,
                accessCountService,
                shortUrlRepository,
                transactionManager,
                urlCacheService,
                zipurlProperties
        );

        urlCacheService.putResolvedUrl(
                "hot001",
                new CachedResolvedUrl("https://example.com/hot", Instant.now().plusSeconds(60))
        );

        String originalUrl = urlShorteningService.resolveOriginalUrl("hot001");

        assertThat(originalUrl).isEqualTo("https://example.com/hot");
        verify(accessCountService).recordAccess("hot001");
        verifyNoInteractions(shortUrlRepository, transactionManager);
        verifyNoInteractions(aliasGenerator);
    }
}
