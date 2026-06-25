package com.docubrain.enclave.pipeline;

import com.docubrain.enclave.model.JobStatus;
import com.docubrain.enclave.repository.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.pubsub.v1.PubsubMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackagerServiceTest {

    @Mock Storage gcsClient;
    @Mock Publisher publisher;
    @Mock JobRepository jobRepository;

    private PackagerService packagerService;
    private final ObjectMapper objectMapper = new ObjectMapper()
        .findAndRegisterModules();

    @BeforeEach
    void setUp() throws Exception {
        com.google.api.core.ApiFuture<String> future =
            com.google.api.core.ApiFutures.immediateFuture("msg-id-123");
        when(publisher.publish(any(PubsubMessage.class))).thenReturn(future);

        packagerService = new PackagerService(
            gcsClient, publisher, objectMapper, jobRepository,
            "docubrain-artifacts-test", System.getProperty("java.io.tmpdir")
        );
    }

    @Test
    void uploadsToGcsAndPublishesToPubSub() throws Exception {
        packagerService.packageAndPublish(
            "job-abc", "src-001", "medical_record", 3,
            "Patient: [REDACTED_PERSON]",
            Map.of("language", "en", "wordCount", 3),
            1, "0.1.0"
        );

        ArgumentCaptor<BlobInfo> blobCaptor = ArgumentCaptor.forClass(BlobInfo.class);
        verify(gcsClient).create(blobCaptor.capture(), any(byte[].class));
        assertThat(blobCaptor.getValue().getName()).isEqualTo("job-abc.json");
        assertThat(blobCaptor.getValue().getContentType()).isEqualTo("application/json");

        verify(publisher).publish(any(PubsubMessage.class));
        verify(jobRepository).updateStatus("job-abc", JobStatus.PUBLISHED);
    }

    @Test
    void gcsUriHasCorrectFormat() throws Exception {
        String uri = packagerService.packageAndPublish(
            "job-xyz", "src-002", "invoice", 1,
            "Amount due: [REDACTED_MONETARY]",
            Map.of("language", "en"),
            0, "0.1.0"
        );

        assertThat(uri).isEqualTo("gs://docubrain-artifacts-test/job-xyz.json");
    }
}
