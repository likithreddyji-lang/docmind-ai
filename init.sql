-- MySQL init script for DocMind
-- Tables are auto-created by Hibernate (ddl-auto=update), but the vector_store table
-- is created here since it's managed by our custom MysqlVectorStore, not JPA.

CREATE DATABASE IF NOT EXISTS docmind;
USE docmind;

CREATE TABLE IF NOT EXISTS vector_store (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    document_id VARCHAR(36),
    user_id VARCHAR(255),
    content LONGTEXT,
    metadata JSON,
    embedding JSON
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
