package com.docubrain.enclave.api;

import com.docubrain.enclave.model.JobStatus;
import com.docubrain.enclave.pipeline.IngestionService;
import com.docubrain.enclave.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final IngestionService ingestionService;
    private final JobRepository jobRepository;

    public IngestController(IngestionService ingestionService, JobRepository jobRepository) {
        this.ingestionService = ingestionService;
        this.jobRepository = jobRepository;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, String>> ingest(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sourceId", defaultValue = "unknown") String sourceId) throws IOException {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File must not be empty"));
        }

        log.info("Received ingest request source_id={} size={}", sourceId, file.getSize());
        String jobId = ingestionService.accept(file, sourceId);

        return ResponseEntity.accepted().body(Map.of(
            "job_id", jobId,
            "status", "RECEIVED"
        ));
    }

    @GetMapping("/status/{jobId}")
    public ResponseEntity<Map<String, String>> status(@PathVariable String jobId) {
        JobStatus status = jobRepository.getStatus(jobId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
            "job_id", jobId,
            "status", status.name()
        ));
    }
}
