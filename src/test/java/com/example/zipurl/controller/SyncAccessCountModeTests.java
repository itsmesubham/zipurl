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

@SpringBootTest(properties = {
        "zipurl.access-count.mode=sync",
        "zipurl.url-cache.mode=local"
})
@AutoConfigureMockMvc
class SyncAccessCountModeTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShortUrlRepository shortUrlRepository;

    @BeforeEach
    void setUp() {
        shortUrlRepository.deleteAll();
    }

    @Test
    void redirectCountsSynchronouslyInSyncMode() throws Exception {
        shortUrlRepository.saveAndFlush(new ShortUrl("sync001", "https://example.com/sync"));

        mockMvc.perform(get("/sync001"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/sync"));

        assertThat(shortUrlRepository.findByAlias("sync001"))
                .hasValueSatisfying(shortUrl -> assertThat(shortUrl.getAccessCount()).isEqualTo(1));
    }
}
