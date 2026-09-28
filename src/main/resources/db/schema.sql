-- Enable pgvector extension (requires superuser / pgvector image)
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Create schema if not exists
CREATE SCHEMA IF NOT EXISTS fship_ai;

CREATE TABLE IF NOT EXISTS fship_ai.app_setting (
    id bigserial PRIMARY KEY,
    config_key varchar(100) NOT NULL UNIQUE,
    config_value text NOT NULL DEFAULT '',
    category varchar(50) NOT NULL DEFAULT 'general',
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP
);

-- Admin-editable LLM prompts.
-- Seeded by db/data.sql (run it after this script). The Java registry
-- util/PromptDefaults holds the same text and is only a fallback for when these
-- rows are missing — PromptDefaultsTest asserts the two never drift.
CREATE TABLE IF NOT EXISTS fship_ai.app_prompt (
    id bigserial PRIMARY KEY,
    prompt_key varchar(100) NOT NULL UNIQUE,
    prompt_group varchar(50) NOT NULL DEFAULT 'chat',
    label varchar(200) NOT NULL DEFAULT '',
    description text NOT NULL DEFAULT '',
    placeholders varchar(200) NOT NULL DEFAULT '',
    content text NOT NULL DEFAULT '',
    sort_order int4 DEFAULT 0,
    updated_at timestamptz DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS fship_ai.app_suggestion (
   id bigserial PRIMARY KEY,
   question text NOT NULL,
   caption text NOT NULL DEFAULT '',
   icon varchar(10) DEFAULT '',
   sort_order int4 DEFAULT 0,
   is_active boolean DEFAULT true,
   created_at timestamptz DEFAULT CURRENT_TIMESTAMP,
   updated_at timestamptz DEFAULT CURRENT_TIMESTAMP
);


-- Rename migration: the table used to be fship_ai.friendship_knowledge_base.
-- Must run BEFORE the CREATE TABLE below, otherwise an existing deployment gets
-- a brand-new empty knowledge_base and silently loses every chunk.
-- Idempotent: once the new table exists the block is a no-op, so schema.sql can
-- be re-run safely.
DO $$
BEGIN
    IF to_regclass('fship_ai.knowledge_base') IS NULL
       AND to_regclass('fship_ai.friendship_knowledge_base') IS NOT NULL THEN
        EXECUTE format('ALTER TABLE %I.%I RENAME TO %I',
                       'fship_ai', 'friendship_knowledge_base', 'knowledge_base');
        RAISE NOTICE 'Renamed the knowledge base table to knowledge_base';
    END IF;
END
$$;

CREATE TABLE IF NOT EXISTS fship_ai.knowledge_base (
    embedding_id uuid DEFAULT gen_random_uuid() NOT NULL,
    embedding vector(384) NULL,
    "text" text NOT NULL,
    metadata jsonb DEFAULT '{}'::jsonb NULL,
    file_name varchar(500) NULL,
    file_type varchar(100) NULL,
    file_hash varchar(64) NULL,
    file_size int8 NULL,
    last_modified timestamptz NULL,
    processed_date timestamptz DEFAULT CURRENT_TIMESTAMP NULL,
    chunk_index int4 NULL,
    total_chunks int4 NULL,
    chunk_start int4 NULL,
    chunk_end int4 NULL,
    "version" int4 DEFAULT 1 NULL,
    -- NOTE: a generated column takes no NULL/NOT NULL clause — PostgreSQL only
    -- accepts <type> GENERATED ALWAYS AS (expr) STORED. A trailing NULL here is
    -- a syntax error that aborts the whole script, so app_prompt never gets
    -- created and every admin prompt edit fails.
    content_tsv tsvector GENERATED ALWAYS AS (to_tsvector('english', COALESCE("text", ''))) STORED,
    CONSTRAINT knowledge_base_pkey PRIMARY KEY (embedding_id)
);

-- Extensions
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Indexes
CREATE INDEX IF NOT EXISTS idx_app_suggestion_active ON fship_ai.app_suggestion (is_active, sort_order);
CREATE INDEX IF NOT EXISTS idx_app_prompt_group ON fship_ai.app_prompt (prompt_group, sort_order);
CREATE INDEX IF NOT EXISTS idx_embedding_id ON fship_ai.knowledge_base USING btree (embedding_id);
CREATE INDEX IF NOT EXISTS idx_file_name ON fship_ai.knowledge_base USING btree (file_name);
CREATE INDEX IF NOT EXISTS idx_metadata ON fship_ai.knowledge_base USING GIN (metadata);
CREATE INDEX IF NOT EXISTS idx_embedding_ivfflat ON fship_ai.knowledge_base USING ivfflat (embedding vector_cosine_ops) WITH (lists = 50);
CREATE INDEX IF NOT EXISTS idx_kb_tsv ON fship_ai.knowledge_base USING GIN (content_tsv);
CREATE INDEX IF NOT EXISTS idx_kb_trgm ON fship_ai.knowledge_base USING GIN ("text" gin_trgm_ops);


