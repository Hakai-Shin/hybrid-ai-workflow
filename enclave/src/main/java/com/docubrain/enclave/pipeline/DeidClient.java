package com.docubrain.enclave.pipeline;

import com.docubrain.enclave.exception.PipelineException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class DeidClient {

    private static final Logger log = LoggerFactory.getLogger(DeidClient.class);

    private static final List<String> ENTITY_TYPES = List.of(
        "PERSON", "EMAIL_ADDRESS", "PHONE_NUMBER", "DATE_TIME",
        "MEDICAL_LICENSE", "US_SSN", "LOCATION", "NRP", "URL", "IP_ADDRESS"
    );

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final int timeoutSeconds;

    public DeidClient(WebClient presidioWebClient,
                      ObjectMapper objectMapper,
                      @Value("${presidio.timeout-seconds:30}") int timeoutSeconds) {
        this.webClient = presidioWebClient;
        this.objectMapper = objectMapper;
        this.timeoutSeconds = timeoutSeconds;
    }

    public record DeidResult(String anonymizedText, int entityCount) {}

    public DeidResult deidentify(String text, String jobId) {
        log.info("Starting de-identification job_id={}", jobId);

        JsonNode analyzerResults = analyze(text);
        DeidResult result = anonymize(text, analyzerResults);

        log.info("De-identification complete job_id={} entity_count={}", jobId, result.entityCount());
        return result;
    }

    private JsonNode analyze(String text) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("text", text);
        body.put("language", "en");
        ArrayNode entities = body.putArray("entities");
        ENTITY_TYPES.forEach(entities::add);

        return webClient.post()
            .uri("/analyze")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .retryWhen(Retry.fixedDelay(1, Duration.ofSeconds(2))
                .filter(e -> !(e instanceof PipelineException)))
            .blockOptional()
            .orElseThrow(() -> new PipelineException("Presidio /analyze returned empty response"));
    }

    private DeidResult anonymize(String text, JsonNode analyzerResults) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("text", text);
        body.set("analyzer_results", analyzerResults);

        ObjectNode operators = body.putObject("operators");
        for (String entity : ENTITY_TYPES) {
            ObjectNode op = operators.putObject(entity);
            op.put("type", "replace");
            ObjectNode params = op.putObject("new_value");
            // value is set by the sidecar based on entity type — just pass the key
            op.put("new_value", "[REDACTED_" + entity + "]");
        }

        JsonNode response = webClient.post()
            .uri("/anonymize")
            .bodyValue(body)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .retryWhen(Retry.fixedDelay(1, Duration.ofSeconds(2))
                .filter(e -> !(e instanceof PipelineException)))
            .blockOptional()
            .orElseThrow(() -> new PipelineException("Presidio /anonymize returned empty response"));

        String anonymizedText = response.path("text").asText();
        int entityCount = response.path("entity_count").asInt();
        return new DeidResult(anonymizedText, entityCount);
    }
}
