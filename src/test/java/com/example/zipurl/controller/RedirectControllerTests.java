package com.example.zipurl.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.zipurl.model.ShortUrl;
import com.example.zipurl.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RedirectControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShortUrlRepository shortUrlRepository;

    @BeforeEach
    void setUp() {
        shortUrlRepository.deleteAll();
    }

    @Test
    void redirectsToOriginalUrlAndTracksAccess() throws Exception {
        shortUrlRepository.saveAndFlush(new ShortUrl("go123", "https://example.com/landing"));

        mockMvc.perform(get("/go123"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/landing"));

        assertThat(shortUrlRepository.findByAlias("go123"))
                .hasValueSatisfying(shortUrl -> assertThat(shortUrl.getAccessCount()).isEqualTo(1));
    }

    @Test
    void returnsNotFoundForMissingAlias() throws Exception {
        mockMvc.perform(get("/missing123"))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsNotFoundForExpiredAlias() throws Exception {
        shortUrlRepository.saveAndFlush(
                new ShortUrl("old123", "https://example.com/old", java.time.Instant.now().minusSeconds(60))
        );

        mockMvc.perform(get("/old123"))
                .andExpect(status().isNotFound());
    }
}
