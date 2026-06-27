package com.example.zipurl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.zipurl.exception.ShortUrlNotFoundException;
import com.example.zipurl.model.ShortUrl;
import com.example.zipurl.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class UrlShorteningServiceTests {

    @Autowired
    private UrlShorteningService urlShorteningService;

    @Autowired
    private ShortUrlRepository shortUrlRepository;

    @BeforeEach
    void setUp() {
        shortUrlRepository.deleteAll();
    }

    @Test
    void resolveOriginalUrlUsesCacheAndIncrementsAccessCount() {
        shortUrlRepository.saveAndFlush(new ShortUrl("cached01", "https://example.com/long"));

        assertThat(urlShorteningService.resolveOriginalUrl("cached01")).isEqualTo("https://example.com/long");
        assertThat(urlShorteningService.resolveOriginalUrl("cached01")).isEqualTo("https://example.com/long");

        assertThat(shortUrlRepository.findByAlias("cached01"))
                .hasValueSatisfying(shortUrl -> assertThat(shortUrl.getAccessCount()).isEqualTo(2));
    }

    @Test
    void resolveOriginalUrlThrowsWhenAliasDoesNotExist() {
        assertThatThrownBy(() -> urlShorteningService.resolveOriginalUrl("missing01"))
                .isInstanceOf(ShortUrlNotFoundException.class);
    }

    @Test
    void getShortUrlReturnsMetadataWithoutIncrementingAccessCount() {
        shortUrlRepository.saveAndFlush(new ShortUrl("meta01", "https://example.com/meta"));

        ShortUrl shortUrl = urlShorteningService.getShortUrl("meta01");

        assertThat(shortUrl.getOriginalUrl()).isEqualTo("https://example.com/meta");
        assertThat(shortUrl.getAccessCount()).isZero();
    }
}
