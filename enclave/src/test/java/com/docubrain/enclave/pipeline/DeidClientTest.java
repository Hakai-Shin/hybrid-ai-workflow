package com.docubrain.enclave.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class DeidClientTest {

    private MockWebServer mockServer;
    private DeidClient deidClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        WebClient webClient = WebClient.builder()
            .baseUrl(mockServer.url("/").toString())
            .build();

        deidClient = new DeidClient(webClient, objectMapper, 5);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    void deidentifiesTextSuccessfully() throws Exception {
        // /analyze response
        mockServer.enqueue(new MockResponse()
            .setBody("[{\"entity_type\":\"PERSON\",\"start\":8,\"end\":16,\"score\":0.85}]")
            .addHeader("Content-Type", "application/json"));

        // /anonymize response
        mockServer.enqueue(new MockResponse()
            .setBody("{\"text\":\"Patient: [REDACTED_PERSON] is here.\",\"entity_count\":1}")
            .addHeader("Content-Type", "application/json"));

        DeidClient.DeidResult result = deidClient.deidentify("Patient: John Doe is here.", "job-001");

        assertThat(result.anonymizedText()).contains("[REDACTED_PERSON]");
        assertThat(result.entityCount()).isEqualTo(1);

        assertThat(mockServer.takeRequest().getPath()).isEqualTo("/analyze");
        assertThat(mockServer.takeRequest().getPath()).isEqualTo("/anonymize");
    }

    @Test
    void returnsZeroEntitiesWhenNoneFound() throws Exception {
        mockServer.enqueue(new MockResponse()
            .setBody("[]")
            .addHeader("Content-Type", "application/json"));

        mockServer.enqueue(new MockResponse()
            .setBody("{\"text\":\"No PHI here.\",\"entity_count\":0}")
            .addHeader("Content-Type", "application/json"));

        DeidClient.DeidResult result = deidClient.deidentify("No PHI here.", "job-002");

        assertThat(result.anonymizedText()).isEqualTo("No PHI here.");
        assertThat(result.entityCount()).isZero();
    }
}
