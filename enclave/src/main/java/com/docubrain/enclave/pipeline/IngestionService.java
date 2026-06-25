package com.docubrain.enclave.pipeline;

import com.docubrain.enclave.exception.PipelineException;
import com.docubrain.enclave.model.JobStatus;
import com.docubrain.enclave.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final OcrService ocrService;
    private final DeidClient deidClient;
    private final MetadataService metadataService;
    private final PackagerService packagerService;
    private final JobRepository jobRepository;
    private final String dataDir;
    private final String enclaveVersion;

    public IngestionService(OcrService ocrService,
                            DeidClient deidClient,
                            MetadataService metadataService,
                            PackagerService packagerService,
                            JobRepository jobRepository,
                            @Value("${enclave.data-dir:/data}") String dataDir,
                            @Value("${enclave.version:0.1.0}") String enclaveVersion) {
        this.ocrService = ocrService;
        this.deidClient = deidClient;
        this.metadataService = metadataService;
        this.packagerService = packagerService;
        this.jobRepository = jobRepository;
        this.dataDir = dataDir;
        this.enclaveVersion = enclaveVersion;
    }

    public String accept(MultipartFile file, String sourceId) throws IOException {
        String jobId = UUID.randomUUID().toString();
        jobRepository.save(jobId, sourceId, JobStatus.RECEIVED);
        log.info("Accepted ingest request job_id={} source_id={}", jobId, sourceId);

        Path rawDir = Path.of(dataDir, "raw", jobId);
        Files.createDirectories(rawDir);
        File storedFile = rawDir.resolve("document").toFile();
        file.transferTo(storedFile);

        processAsync(jobId, sourceId, storedFile);
        return jobId;
    }

    @Async
    public void processAsync(String jobId, String sourceId, File file) {
        try {
            jobRepository.updateStatus(jobId, JobStatus.PROCESSING);

            OcrService.OcrResult ocr = ocrService.extract(file, jobId);
            DeidClient.DeidResult deid = deidClient.deidentify(ocr.text(), jobId);
            Map<String, Object> metadata = metadataService.extract(
                deid.anonymizedText(), ocr.detectedFormat(), "en"
            );
            String docTypeHint = (String) metadata.get("docTypeHint");

            packagerService.packageAndPublish(
                jobId, sourceId, docTypeHint, ocr.pageCount(),
                deid.anonymizedText(), metadata, deid.entityCount(), enclaveVersion
            );
        } catch (PipelineException e) {
            log.error("Pipeline failed job_id={} error={}", jobId, e.getMessage());
            jobRepository.updateStatus(jobId, JobStatus.FAILED, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error job_id={}", jobId, e);
            jobRepository.updateStatus(jobId, JobStatus.FAILED, e.getMessage());
        }
    }
}
