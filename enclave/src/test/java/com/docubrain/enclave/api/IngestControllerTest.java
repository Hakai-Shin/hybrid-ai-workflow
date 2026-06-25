package com.docubrain.enclave.api;

import com.docubrain.enclave.model.JobStatus;
import com.docubrain.enclave.pipeline.IngestionService;
import com.docubrain.enclave.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IngestController.class)
class IngestControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean IngestionService ingestionService;
    @MockBean JobRepository jobRepository;

    @Test
    void postIngestReturns202WithJobId() throws Exception {
        when(ingestionService.accept(any(), eq("src-001"))).thenReturn("job-abc");

        MockMultipartFile file = new MockMultipartFile(
            "file", "report.pdf", "application/pdf", "PDF content".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/ingest")
                .file(file)
                .param("sourceId", "src-001"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.job_id").value("job-abc"))
            .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    void postIngestRejectEmptyFile() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile(
            "file", "empty.pdf", "application/pdf", new byte[0]
        );

        mockMvc.perform(multipart("/api/v1/ingest").file(emptyFile))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getStatusReturns200ForKnownJob() throws Exception {
        when(jobRepository.getStatus("job-abc")).thenReturn(JobStatus.PUBLISHED);

        mockMvc.perform(get("/api/v1/status/job-abc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.job_id").value("job-abc"))
            .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void getStatusReturns404ForUnknownJob() throws Exception {
        when(jobRepository.getStatus("missing")).thenReturn(null);

        mockMvc.perform(get("/api/v1/status/missing"))
            .andExpect(status().isNotFound());
    }
}
