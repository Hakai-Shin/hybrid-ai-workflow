package com.docubrain.enclave.config;

import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.pubsub.v1.TopicName;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;

@Configuration
public class AppConfig {

    @Bean
    public Storage gcsClient(@Value("${gcp.project-id}") String projectId) {
        return StorageOptions.newBuilder()
            .setProjectId(projectId)
            .build()
            .getService();
    }

    @Bean
    public Publisher pubsubPublisher(
            @Value("${gcp.project-id}") String projectId,
            @Value("${gcp.pubsub-topic:artifact-intake}") String topicId) throws IOException {
        return Publisher.newBuilder(TopicName.of(projectId, topicId)).build();
    }

    @Bean
    public WebClient presidioWebClient(@Value("${presidio.url:http://presidio-sidecar:8080}") String presidioUrl) {
        return WebClient.builder()
            .baseUrl(presidioUrl)
            .build();
    }
}
