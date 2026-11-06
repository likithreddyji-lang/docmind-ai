package com.substring.docmind.config;

import com.substring.docmind.vectorstore.MysqlVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class VectorStoreConfig {

    @Bean
    public MysqlVectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        MysqlVectorStore store = new MysqlVectorStore(jdbcTemplate, embeddingModel);
        store.initSchema();
        return store;
    }
}

