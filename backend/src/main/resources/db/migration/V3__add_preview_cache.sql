-- V3: persistent preview cache (L2). L1 = in-memory ConcurrentHashMap.
-- Переживает restart сервера — популярные товары (Ozon/Wildberries) не требуют
-- повторного Playwright-парсинга после deploy.

CREATE TABLE IF NOT EXISTS preview_cache (
    url VARCHAR(2048) PRIMARY KEY,
    response_json TEXT NOT NULL,
    expires_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_preview_cache_expires ON preview_cache(expires_at);
