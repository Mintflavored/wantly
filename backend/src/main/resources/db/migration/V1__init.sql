-- V1__init.sql — начальная схема Wantly backend.
--
-- Совпадает с существующими Exposed Table definitions (Users, Wishlists, Wishes).
-- Использует CREATE TABLE IF NOT EXISTS — корректно работает как на свежей БД,
-- так и при baselineOnMigrate=true (если SchemaUtils.create уже создал таблицы
-- в старом релизе, Flyway их не трогает).

CREATE TABLE IF NOT EXISTS users (
    id           BIGSERIAL    PRIMARY KEY,
    email        VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS wishlists (
    id          BIGSERIAL    PRIMARY KEY,
    owner_id    BIGINT       NOT NULL REFERENCES users(id),
    title       VARCHAR(200) NOT NULL,
    description TEXT,
    is_shared   BOOLEAN      NOT NULL DEFAULT FALSE,
    cover_color INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_wishlists_owner_id ON wishlists(owner_id);

CREATE TABLE IF NOT EXISTS wishes (
    id          BIGSERIAL    PRIMARY KEY,
    wishlist_id BIGINT       NOT NULL REFERENCES wishlists(id) ON DELETE CASCADE,
    title       VARCHAR(500) NOT NULL,
    description TEXT,
    url         TEXT,
    image_url   TEXT,
    price       DOUBLE PRECISION,
    currency    VARCHAR(3)   NOT NULL DEFAULT 'RUB',
    store_name  VARCHAR(200),
    status      VARCHAR(20)  NOT NULL DEFAULT 'WANTED',
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_wishes_wishlist_id ON wishes(wishlist_id);
