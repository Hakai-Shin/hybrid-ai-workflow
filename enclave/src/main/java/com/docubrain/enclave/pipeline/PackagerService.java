package com.docubrain.enclave.pipeline;

import com.docubrain.enclave.exception.PipelineException;
import com.docubrain.enclave.model.ArtifactPackage;
import com.docubrain.enclave.model.JobStatus;
import com.docubrain.enclave.repository.JobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.core.ApiFuture;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

@Service
public class PackagerService {

    private static final Logger log = LoggerFactory.getLogger(PackagerService.class);

    private final Storage gcsClient;
    private final Publisher pubsubPublisher;
    private final ObjectMapper objectMapper;
    private final JobRepository jobRepository;
    private final String artifactBucket;
    private final String dataDir;

    public PackagerService(Storage gcsClient,
                           Publisher pubsubPublisher,
                           ObjectMapper objectMapper,
                           JobRepository jobRepository,
                           @Value("${gcp.artifact-bucket:docubrain-artifacts-dev}") String artifactBucket,
                           @Value("${enclave.data-dir:/data}") String dataDir) {
        this.gcsClient = gcsClient;
        this.pubsubPublisher = pubsubPublisher;
        this.objectMapper = objectMapper;
        this.jobRepository = jobRepository;
        this.artifactBucket = artifactBucket;
        this.dataDir = dataDir;
    }

    public String packageAndPublish(String jobId,
                                    String sourceId,
                                    String docTypeHint,
                                    int pageCount,
                                    String redactedText,
                                    Map<String, Object> metadata,
                                    int phiEntityCount,
                                    String enclaveVersion) {
        ArtifactPackage pkg = new ArtifactPackage(
            jobId, sourceId, docTypeHint, pageCount,
            redactedText, metadata, phiEntityCount,
            Instant.now(), enclaveVersion
        );

        try {
            byte[] json = objectMapper.writeValueAsBytes(pkg);
            String gcsPath = jobId + ".json";
            BlobId blobId = BlobId.of(artifactBucket, gcsPath);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType("application/json")
                .build();
            gcsClient.create(blobInfo, json);
            String gcsUri = "gs://" + artifactBucket + "/" + gcsPath;
            log.info("Uploaded artifact to GCS job_id={} uri={}", jobId, gcsUri);

            String messageJson = objectMapper.writeValueAsString(
                Map.of("job_id", jobId, "gcs_uri", gcsUri)
            );
            PubsubMessage message = PubsubMessage.newBuilder()
                .setData(ByteString.copyFromUtf8(messageJson))
                .putAttributes("job_id", jobId)
                .build();
            ApiFuture<String> future = pubsubPublisher.publish(message);
            String messageId = future.get();
            log.info("Published Pub/Sub message job_id={} message_id={}", jobId, messageId);

            jobRepository.updateStatus(jobId, JobStatus.PUBLISHED);

            Path rawDir = Path.of(dataDir, "raw", jobId);
            if (Files.exists(rawDir)) {
                Files.walk(rawDir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
                log.info("Deleted raw files job_id={}", jobId);
            }

            return gcsUri;
        } catch (Exception e) {
            throw new PipelineException("Packaging failed for job " + jobId, e);
        }
    }
}
