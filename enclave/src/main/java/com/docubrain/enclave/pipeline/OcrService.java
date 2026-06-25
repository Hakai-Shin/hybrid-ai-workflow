package com.docubrain.enclave.pipeline;

import com.docubrain.enclave.exception.PipelineException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private final AutoDetectParser tikaParser;

    public OcrService() {
        this.tikaParser = new AutoDetectParser();
    }

    public record OcrResult(String text, int pageCount, String detectedFormat) {}

    public OcrResult extract(File file, String jobId) {
        log.info("Starting OCR extraction job_id={}", jobId);
        try (var stream = new FileInputStream(file)) {
            var handler = new BodyContentHandler(-1);
            var metadata = new Metadata();
            tikaParser.parse(stream, handler, metadata);

            String text = handler.toString().trim();
            String format = metadata.get(org.apache.tika.metadata.HttpHeaders.CONTENT_TYPE);
            int pageCount = parsePageCount(metadata);

            if (text.isEmpty()) {
                log.info("Tika returned empty text, falling back to Tesseract job_id={}", jobId);
                return tesseractFallback(file, jobId, format);
            }

            log.info("OCR complete job_id={} pages={} format={}", jobId, pageCount, format);
            return new OcrResult(text, pageCount, format);
        } catch (Exception e) {
            throw new PipelineException("OCR extraction failed for job " + jobId, e);
        }
    }

    private OcrResult tesseractFallback(File file, String jobId, String format) {
        try {
            Path tempDir = Files.createTempDirectory("docubrain-ocr-" + jobId);
            Path pdfPath = file.toPath();

            // Convert PDF pages to PNGs via pdfbox CLI, then run Tesseract
            ProcessBuilder pdfToImg = new ProcessBuilder(
                "java", "-jar", "pdfbox-app.jar", "PDFToImage",
                "-outputPrefix", tempDir.resolve("page").toString(),
                "-imageType", "png",
                pdfPath.toString()
            );
            pdfToImg.redirectErrorStream(true);
            Process pdfProc = pdfToImg.start();
            pdfProc.waitFor();

            File[] pages = tempDir.toFile().listFiles((d, n) -> n.endsWith(".png"));
            if (pages == null || pages.length == 0) {
                return new OcrResult("", 0, format);
            }

            StringBuilder combined = new StringBuilder();
            for (File page : pages) {
                Path txtOut = tempDir.resolve(page.getName().replace(".png", ""));
                ProcessBuilder tess = new ProcessBuilder("tesseract", page.getAbsolutePath(), txtOut.toString());
                tess.redirectErrorStream(true);
                Process tessProc = tess.start();
                tessProc.waitFor();

                File txtFile = new File(txtOut + ".txt");
                if (txtFile.exists()) {
                    combined.append(Files.readString(txtFile.toPath())).append("\n");
                }
            }

            int pageCount = pages.length;
            log.info("Tesseract fallback complete job_id={} pages={}", jobId, pageCount);
            return new OcrResult(combined.toString().trim(), pageCount, format);
        } catch (Exception e) {
            throw new PipelineException("Tesseract fallback failed for job " + jobId, e);
        }
    }

    private int parsePageCount(Metadata metadata) {
        String pages = metadata.get("xmpTPg:NPages");
        if (pages == null) pages = metadata.get("pdf:charsPerPage");
        try {
            return pages != null ? Integer.parseInt(pages.trim()) : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
