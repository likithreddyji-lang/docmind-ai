package com.substring.docmind.vectorstore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Custom EmbeddingModel for Google Gemini via its OpenAI-compatible /v1beta/openai/embeddings endpoint.
 * Directly parses the JSON embedding response, avoiding openai-java-core validation errors
 * when Gemini omits the 'index' field.
 */
@Component
@Primary
public class GeminiEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingModel.class);

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GeminiEmbeddingModel(
            @Value("${spring.ai.openai.api-key:${GEMINI_API_KEY:}}") String apiKey,
            @Value("${spring.ai.openai.base-url:https://generativelanguage.googleapis.com/v1beta/openai}") String baseUrl,
            @Value("${spring.ai.openai.embedding.options.model:${GEMINI_EMBEDDING_MODEL:gemini-embedding-001}}") String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
        log.info("Initialized GeminiEmbeddingModel with baseUrl={}, model={}", baseUrl, model);
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public float[] embed(String text) {
        return fetchEmbedding(text);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<Embedding> embeddings = new ArrayList<>();
        List<String> instructions = request.getInstructions();
        for (int i = 0; i < instructions.size(); i++) {
            float[] vector = fetchEmbedding(instructions.get(i));
            embeddings.add(new Embedding(vector, i));
        }
        return new EmbeddingResponse(embeddings);
    }

    private float[] fetchEmbedding(String text) {
        int maxRetries = 5;
        long backoff = 2000L;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                Map<String, Object> body = Map.of(
                        "model", this.model,
                        "input", text
                );
                String jsonBody = objectMapper.writeValueAsString(body);

                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/embeddings"))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(60))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 429 || response.statusCode() == 503) {
                    if (attempt < maxRetries) {
                        log.warn("Gemini embedding rate limit / high load (HTTP {}). Attempt {}/{}. Retrying in {} ms...",
                                response.statusCode(), attempt, maxRetries, backoff);
                        Thread.sleep(backoff);
                        backoff *= 2;
                        continue;
                    }
                }

                if (response.statusCode() != 200) {
                    throw new RuntimeException("Gemini embedding error: HTTP " + response.statusCode() + " - " + response.body());
                }

                JsonNode root = objectMapper.readTree(response.body());
                JsonNode embeddingArray = root.path("data").get(0).path("embedding");
                if (embeddingArray.isMissingNode() || !embeddingArray.isArray()) {
                    throw new RuntimeException("Invalid embedding response structure: " + response.body());
                }

                float[] vector = new float[embeddingArray.size()];
                for (int i = 0; i < embeddingArray.size(); i++) {
                    vector[i] = (float) embeddingArray.get(i).asDouble();
                }
                return vector;

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Embedding interrupted", ie);
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    log.error("Gemini embedding failed after {} attempts", maxRetries, e);
                    throw new RuntimeException("Gemini embedding failed: " + e.getMessage(), e);
                }
                log.warn("Gemini embedding attempt {} failed: {}. Retrying in {} ms...", attempt, e.getMessage(), backoff);
                try {
                    Thread.sleep(backoff);
                    backoff *= 2;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Embedding interrupted", ie);
                }
            }
        }
        throw new RuntimeException("Failed to generate embedding after retries");
    }
}

