package com.docubrain.enclave.model;

import org.springframework.web.multipart.MultipartFile;

public record IngestRequest(
    MultipartFile file,
    String sourceId
) {}
