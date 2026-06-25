package com.docubrain.enclave.pipeline;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class MetadataService {

    private static final int SCAN_CHARS = 500;

    private static final Pattern DATE_PATTERN = Pattern.compile(
        "\\b(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}|\\d{4}[/-]\\d{1,2}[/-]\\d{1,2}|" +
        "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.?\\s+\\d{1,2},?\\s+\\d{4})\\b",
        Pattern.CASE_INSENSITIVE
    );

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        DateTimeFormatter.ofPattern("MM-dd-yyyy"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("d MMM yyyy"),
        DateTimeFormatter.ofPattern("d MMMM yyyy"),
        DateTimeFormatter.ofPattern("MMM d, yyyy"),
        DateTimeFormatter.ofPattern("MMMM d, yyyy")
    );

    public record DocMetadata(
        String docTypeHint,
        String date,
        String language,
        int wordCount
    ) {}

    public Map<String, Object> extract(String redactedText, String detectedFormat, String language) {
        String probe = redactedText.length() > SCAN_CHARS
            ? redactedText.substring(0, SCAN_CHARS).toLowerCase()
            : redactedText.toLowerCase();

        Map<String, Object> meta = new HashMap<>();
        meta.put("docTypeHint", detectDocType(probe));
        meta.put("date", extractDate(probe));
        meta.put("language", language != null ? language : "en");
        meta.put("fileFormat", detectedFormat);
        meta.put("wordCount", countWords(redactedText));
        return meta;
    }

    public String detectDocType(String probe) {
        if (probe.matches("(?s).*(?:icd-10|icd10|diagnosis|patient|chief complaint|medication|rx:|dob:).*")) {
            return "medical_record";
        }
        if (probe.matches("(?s).*(?:invoice|purchase order|amount due|bill to|po number).*")) {
            return "invoice";
        }
        if (probe.matches("(?s).*(?:agreement|whereas|parties|witnesseth|hereinafter).*")) {
            return "contract";
        }
        return "unknown";
    }

    private String extractDate(String probe) {
        Matcher m = DATE_PATTERN.matcher(probe);
        if (!m.find()) return null;
        String raw = m.group().trim();
        for (DateTimeFormatter fmt : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(raw, fmt).toString();
            } catch (DateTimeParseException ignored) {}
        }
        return raw;
    }

    private int countWords(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.trim().split("\\s+").length;
    }
}
