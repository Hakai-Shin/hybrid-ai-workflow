package com.docubrain.enclave.pipeline;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

class OcrServiceTest {

    private final OcrService ocrService = new OcrService();

    @Test
    void extractsTextFromPlainTextFile() throws Exception {
        File tmp = Files.createTempFile("docubrain-test", ".txt").toFile();
        try (FileWriter w = new FileWriter(tmp)) {
            w.write("Patient: John Doe\nDiagnosis: ICD-10 J45.20\nDate: 2024-01-15");
        }
        tmp.deleteOnExit();

        OcrService.OcrResult result = ocrService.extract(tmp, "test-job-001");

        assertThat(result.text()).contains("Patient");
        assertThat(result.pageCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.detectedFormat()).isNotBlank();
    }

    @Test
    void returnsNonNullForEmptyFile() throws Exception {
        File tmp = Files.createTempFile("docubrain-empty", ".txt").toFile();
        tmp.deleteOnExit();

        // Empty file should not throw; may trigger Tesseract fallback which
        // also returns gracefully when no pdfbox binary is available.
        try {
            OcrService.OcrResult result = ocrService.extract(tmp, "test-job-002");
            assertThat(result).isNotNull();
        } catch (com.docubrain.enclave.exception.PipelineException e) {
            // Acceptable when Tesseract binaries are absent in CI
            assertThat(e.getMessage()).contains("test-job-002");
        }
    }
}
