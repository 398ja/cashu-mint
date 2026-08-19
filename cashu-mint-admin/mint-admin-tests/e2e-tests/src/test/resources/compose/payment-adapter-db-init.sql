-- payment-adapter V6__add_quote_created_at.sql runs ALTER TABLE quote, but no
-- migration in that service creates `quote` — Hibernate's hbm2ddl does, and
-- Flyway runs first. The migration only ever succeeded on databases where
-- Hibernate had already made the table, so it fails on a fresh one, which is
-- what this stack has every run.
--
-- Creating the table with just its primary key is enough: V6 is
-- ADD COLUMN IF NOT EXISTS, and GatewayQuote is mapped with GenerationType.AUTO
-- and spring.jpa.hibernate.ddl-auto=update, so Hibernate adds the remaining
-- columns and its own sequence on startup.
CREATE TABLE IF NOT EXISTS quote (
    id BIGINT PRIMARY KEY
);
