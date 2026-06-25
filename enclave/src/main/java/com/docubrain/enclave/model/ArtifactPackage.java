package com.docubrain.enclave.model;

import java.time.Instant;
import java.util.Map;

public record ArtifactPackage(
    String jobId,
    String sourceId,
    String docTypeHint,
    int pageCount,
    String redactedText,
    Map<String, Object> metadata,
    int phiEntityCount,
    Instant createdAt,
    String enclaveVersion
) {}
