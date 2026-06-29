package com.example.zipurl;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.threads.virtual.enabled=true",
        "zipurl.url-cache.mode=local",
        "zipurl.access-count.mode=disabled"
})
class VirtualThreadsStartupTests {

    @Test
    void contextLoadsWithVirtualThreadsEnabled() {
    }
}
