package com.example.zipurl.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class DeployedAppIntegrationTests {

    private static final String DEFAULT_BASE_URL = "https://goldfish-app-gvvnj.ondigitalocean.app/health";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static String baseUrl;

    @BeforeAll
    static void requireDeploymentBaseUrl() {
        assumeTrue(runDeployedIntegrationTests(),
                "Set ZIPURL_RUN_DEPLOYED_INTEGRATION_TESTS=true to run deployed-app integration tests");

        String configuredBaseUrl = System.getenv("ZIPURL_INTEGRATION_BASE_URL");
        if (configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
            configuredBaseUrl = DEFAULT_BASE_URL;
        }

        baseUrl = normalizeBaseUrl(configuredBaseUrl);
    }

    @Test
    void healthEndpointReportsUp() throws Exception {
        HttpResponse<String> response = get("/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("status").asText()).isEqualTo("UP");
    }

    @Test
    void createMetadataAndRedirectRoundTripWorks() throws Exception {
        String alias = "it" + System.currentTimeMillis();
        String originalUrl = "https://example.com/zipurl-integration";

        HttpResponse<String> createResponse = postJson("/api/urls", """
                {
                  "longUrl": "%s",
                  "customAlias": "%s"
                }
                """.formatted(originalUrl, alias));

        assertThat(createResponse.statusCode()).isEqualTo(201);
        JsonNode created = json(createResponse);
        assertThat(created.path("alias").asText()).isEqualTo(alias);
        assertThat(created.path("originalUrl").asText()).isEqualTo(originalUrl);
        assertThat(created.path("accessCount").asLong()).isZero();

        HttpResponse<String> metadataResponse = get("/api/urls/" + alias);
        assertThat(metadataResponse.statusCode()).isEqualTo(200);
        assertThat(json(metadataResponse).path("alias").asText()).isEqualTo(alias);

        HttpResponse<String> redirectResponse = get("/" + alias);
        assertThat(redirectResponse.statusCode()).isEqualTo(302);
        assertThat(redirectResponse.headers().firstValue("Location")).contains(originalUrl);

        HttpResponse<String> metadataAfterRedirectResponse = get("/api/urls/" + alias);
        assertThat(metadataAfterRedirectResponse.statusCode()).isEqualTo(200);
        assertThat(json(metadataAfterRedirectResponse).path("accessCount").asLong()).isEqualTo(1);
    }

    @Test
    void createRejectsInvalidUrl() throws Exception {
        HttpResponse<String> response = postJson("/api/urls", """
                {
                  "longUrl": "not-a-url"
                }
                """);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response).path("message").asText()).contains("valid URL");
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postJson(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode json(HttpResponse<String> response) throws Exception {
        return OBJECT_MAPPER.readTree(response.body());
    }

    private static URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private static String normalizeBaseUrl(String configuredBaseUrl) {
        String normalized = configuredBaseUrl.strip();
        if (normalized.endsWith("/health")) {
            normalized = normalized.substring(0, normalized.length() - "/health".length());
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    private static boolean runDeployedIntegrationTests() {
        return Boolean.getBoolean("zipurl.runDeployedIntegrationTests")
                || "true".equalsIgnoreCase(System.getenv("ZIPURL_RUN_DEPLOYED_INTEGRATION_TESTS"));
    }
}
