-- V2: share_token для публичного доступа к wishlist через GET /api/shared/{token}.
-- Nullable — только isShared=true списки имеют token. UNIQUE INDEX гарантирует,
-- что два списка не получат один токен (генерация SecureRandom 16 bytes,
-- коллизия крайне маловероятна, но БД-констрейнт — последняя линия защиты).
-- VARCHAR(32) с запасом: base64url от 16 байт = 22 символа.

ALTER TABLE wishlists ADD COLUMN share_token VARCHAR(32) UNIQUE;

-- Частичный индекс: только non-null tokens (большинство списков не shared).
-- Ускоряет публичный lookup GET /api/shared/{token} до O(log n) по shared-множеству.
CREATE INDEX IF NOT EXISTS idx_wishlists_share_token
    ON wishlists(share_token)
    WHERE share_token IS NOT NULL;
