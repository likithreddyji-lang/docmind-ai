package com.substring.docmind.vectorstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * A custom VectorStore implementation backed by a MySQL table.
 * Stores embeddings as JSON arrays and performs cosine similarity in Java.
 * Implements retry with exponential backoff on embedding generation to handle HTTP 429 rate limits.
 */
public class MysqlVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(MysqlVectorStore.class);
    private static final String TABLE_NAME = "vector_store";

    private static final int MAX_RETRIES = 5;
    private static final long INITIAL_BACKOFF_MS = 2000L;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    public MysqlVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Initialize the vector_store table if it doesn't exist.
     */
    public void initSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS vector_store (
                id VARCHAR(36) NOT NULL PRIMARY KEY,
                document_id VARCHAR(36),
                user_id VARCHAR(255),
                content LONGTEXT,
                metadata JSON,
                embedding JSON,
                INDEX idx_doc (document_id),
                INDEX idx_user (user_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
            """);
        log.info("MySQL vector_store table initialized");
    }

    @Override
    public void add(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }

        // Embed each document with retry/backoff for free tier rate limits
        List<float[]> embeddings = new ArrayList<>();
        for (Document doc : documents) {
            float[] emb = embedWithRetry(doc.getText());
            embeddings.add(emb);
        }

        String sql = "INSERT INTO " + TABLE_NAME + " (id, document_id, user_id, content, metadata, embedding) " +
                "VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE content=VALUES(content), metadata=VALUES(metadata), embedding=VALUES(embedding)";

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            float[] embedding = embeddings.get(i);
            Map<String, Object> metadata = doc.getMetadata();

            String id = doc.getId() != null ? doc.getId() : UUID.randomUUID().toString();
            String documentId = metadata.getOrDefault("documentId", "").toString();
            String userId = metadata.getOrDefault("userId", "").toString();
            String content = doc.getText();

            try {
                String metadataJson = objectMapper.writeValueAsString(metadata);
                String embeddingJson = objectMapper.writeValueAsString(embedding);

                jdbcTemplate.update(sql, id, documentId, userId, content, metadataJson, embeddingJson);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize document metadata/embedding for doc id: {}", id, e);
                throw new RuntimeException("Failed to serialize document data", e);
            }
        }
        log.info("Added {} documents to MySQL vector store", documents.size());
    }

    @Override
    public void delete(List<String> idList) {
        if (idList == null || idList.isEmpty()) {
            return;
        }
        String placeholders = idList.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = "DELETE FROM " + TABLE_NAME + " WHERE id IN (" + placeholders + ")";
        int deleted = jdbcTemplate.update(sql, idList.toArray());
        log.info("Deleted {} vectors from MySQL vector store by id list", deleted);
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
        if (filterExpression == null) {
            return;
        }
        List<Object> params = new ArrayList<>();
        String whereClause = buildWhereClause(filterExpression, params);
        if (whereClause.isEmpty()) {
            log.warn("Empty where clause generated for delete filter expression, skipping");
            return;
        }
        String sql = "DELETE FROM " + TABLE_NAME + " WHERE " + whereClause;
        int deleted = jdbcTemplate.update(sql, params.toArray());
        log.info("Deleted {} vectors from MySQL vector store by filter expression", deleted);
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        // 1. Embed the query with retry
        float[] queryEmbedding = embedWithRetry(request.getQuery());

        // 2. Build SQL filter from FilterExpression
        StringBuilder sqlBuilder = new StringBuilder("SELECT id, content, metadata, embedding FROM " + TABLE_NAME);
        List<Object> params = new ArrayList<>();

        Filter.Expression filterExpression = request.getFilterExpression();
        if (filterExpression != null) {
            String whereClause = buildWhereClause(filterExpression, params);
            if (!whereClause.isEmpty()) {
                sqlBuilder.append(" WHERE ").append(whereClause);
            }
        }

        // 3. Load candidate vectors from MySQL
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sqlBuilder.toString(), params.toArray());

        // 4. Compute cosine similarity in Java and rank
        List<DocumentWithScore> scored = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            try {
                String embeddingJson = (String) row.get("embedding");
                float[] storedEmbedding = parseEmbeddingJson(embeddingJson);

                double similarity = cosineSimilarity(queryEmbedding, storedEmbedding);

                if (similarity >= request.getSimilarityThreshold()) {
                    String id = (String) row.get("id");
                    String content = (String) row.get("content");
                    String metadataJson = (String) row.get("metadata");
                    Map<String, Object> metadata = objectMapper.readValue(
                            metadataJson, new TypeReference<Map<String, Object>>() {});

                    // Add distance as metadata for compatibility
                    metadata.put("distance", 1.0 - similarity);

                    Document doc = new Document(id, content, metadata);
                    scored.add(new DocumentWithScore(doc, similarity));
                }
            } catch (Exception e) {
                log.warn("Failed to process vector row: {}", e.getMessage());
            }
        }

        // 5. Sort by similarity descending and limit to topK
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        int topK = request.getTopK();
        if (topK > 0 && scored.size() > topK) {
            scored = scored.subList(0, topK);
        }

        log.info("Similarity search returned {} results (topK={}, threshold={})",
                scored.size(), topK, request.getSimilarityThreshold());

        return scored.stream().map(ds -> ds.document).collect(Collectors.toList());
    }

    /**
     * Delete all vectors associated with a specific document ID.
     */
    public int deleteByDocumentId(String documentId) {
        String sql = "DELETE FROM " + TABLE_NAME + " WHERE document_id = ?";
        int deleted = jdbcTemplate.update(sql, documentId);
        log.info("Deleted {} vectors for document_id={}", deleted, documentId);
        return deleted;
    }

    /**
     * Embed text with retry and exponential backoff to handle HTTP 429 rate limit errors.
     */
    private float[] embedWithRetry(String text) {
        long backoff = INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return embeddingModel.embed(text);
            } catch (Exception e) {
                boolean isRateLimit = e.getMessage() != null &&
                        (e.getMessage().contains("429") || e.getMessage().toLowerCase().contains("rate limit") || e.getMessage().toLowerCase().contains("quota"));
                if (isRateLimit && attempt < MAX_RETRIES) {
                    log.warn("Rate limit hit (429) on embedding attempt {}/{}. Backing off for {} ms...", attempt, MAX_RETRIES, backoff);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Embedding retry interrupted", ie);
                    }
                    backoff *= 2;
                } else if (attempt == MAX_RETRIES) {
                    log.error("Failed to embed after {} attempts: {}", MAX_RETRIES, e.getMessage());
                    throw e;
                } else {
                    throw e;
                }
            }
        }
        throw new RuntimeException("Embedding failed after maximum retries");
    }

    /**
     * Build a SQL WHERE clause from a Spring AI Filter.Expression.
     * Uses the composite record structure: Filter.Expression(ExpressionType type, Operand left, Operand right).
     */
    private String buildWhereClause(Filter.Expression expression, List<Object> params) {
        if (expression == null) {
            return "";
        }

        Filter.ExpressionType type = expression.type();

        // Handle logical operators (AND, OR, NOT)
        if (type == Filter.ExpressionType.AND) {
            String left = buildWhereClause((Filter.Expression) expression.left(), params);
            String right = buildWhereClause((Filter.Expression) expression.right(), params);
            if (left.isEmpty()) return right;
            if (right.isEmpty()) return left;
            return "(" + left + " AND " + right + ")";
        }

        if (type == Filter.ExpressionType.OR) {
            String left = buildWhereClause((Filter.Expression) expression.left(), params);
            String right = buildWhereClause((Filter.Expression) expression.right(), params);
            if (left.isEmpty()) return right;
            if (right.isEmpty()) return left;
            return "(" + left + " OR " + right + ")";
        }

        if (type == Filter.ExpressionType.NOT) {
            String inner = buildWhereClause((Filter.Expression) expression.left(), params);
            if (inner.isEmpty()) return "";
            return "NOT (" + inner + ")";
        }

        // Handle comparison operators (EQ, NE, GT, GTE, LT, LTE)
        if (type == Filter.ExpressionType.EQ || type == Filter.ExpressionType.NE ||
                type == Filter.ExpressionType.GT || type == Filter.ExpressionType.GTE ||
                type == Filter.ExpressionType.LT || type == Filter.ExpressionType.LTE) {

            Filter.Key key = (Filter.Key) expression.left();
            Filter.Value value = (Filter.Value) expression.right();
            String keyName = key.key();

            String column = mapKeyToColumn(keyName);

            String operator = switch (type) {
                case EQ -> "=";
                case NE -> "!=";
                case GT -> ">";
                case GTE -> ">=";
                case LT -> "<";
                case LTE -> "<=";
                default -> "=";
            };

            params.add(value.value().toString());
            return column + " " + operator + " ?";
        }

        // Handle IN and NIN
        if (type == Filter.ExpressionType.IN || type == Filter.ExpressionType.NIN) {
            Filter.Key key = (Filter.Key) expression.left();
            Filter.Value value = (Filter.Value) expression.right();
            String keyName = key.key();
            String column = mapKeyToColumn(keyName);

            @SuppressWarnings("unchecked")
            List<?> values = (List<?>) value.value();
            String placeholders = values.stream().map(v -> "?").collect(Collectors.joining(","));
            values.forEach(v -> params.add(v.toString()));

            String op = (type == Filter.ExpressionType.IN) ? "IN" : "NOT IN";
            return column + " " + op + " (" + placeholders + ")";
        }

        log.warn("Unhandled filter expression type: {}", type);
        return "";
    }

    /**
     * Map a metadata key name to a MySQL column reference.
     */
    private String mapKeyToColumn(String keyName) {
        if ("userId".equals(keyName)) {
            return "user_id";
        } else if ("documentId".equals(keyName)) {
            return "document_id";
        } else {
            return "JSON_UNQUOTE(JSON_EXTRACT(metadata, '$." + keyName + "'))";
        }
    }

    private float[] parseEmbeddingJson(String json) throws JsonProcessingException {
        List<Double> list = objectMapper.readValue(json, new TypeReference<List<Double>>() {});
        float[] result = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = list.get(i).floatValue();
        }
        return result;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            log.warn("Embedding dimension mismatch: {} vs {}", a.length, b.length);
            return 0.0;
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record DocumentWithScore(Document document, double score) {}
}
