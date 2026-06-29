package com.example.zipurl.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.zipurl.service.UrlShorteningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RedirectController.class)
class RedirectControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UrlShorteningService urlShorteningService;

    @Test
    void redirectsToOriginalUrlUsingServletResponse() throws Exception {
        when(urlShorteningService.resolveOriginalUrl("go123")).thenReturn("https://example.com/landing");

        mockMvc.perform(get("/go123"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/landing"));

        verify(urlShorteningService).resolveOriginalUrl("go123");
    }
}
