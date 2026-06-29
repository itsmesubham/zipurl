package com.example.zipurl;

import com.example.zipurl.config.ZipurlProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.beans.factory.annotation.Value;

@EnableScheduling
@SpringBootApplication
public class ZipurlApplication {

    private static final Logger log = LoggerFactory.getLogger(ZipurlApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(ZipurlApplication.class, args);
    }

    @Bean
    @ConditionalOnBean(ZipurlProperties.class)
    ApplicationRunner startupConfigLogger(
            @Value("${server.tomcat.threads.max}") int tomcatMaxThreads,
            ZipurlProperties zipurlProperties
    ) {
        return args -> log.info(
                "Startup config: tomcatMaxThreads={}, accessCountMode={}, cacheMaxSize={}",
                tomcatMaxThreads,
                zipurlProperties.getAccessCountMode(),
                zipurlProperties.getCacheMaxSize()
        );
    }
}
