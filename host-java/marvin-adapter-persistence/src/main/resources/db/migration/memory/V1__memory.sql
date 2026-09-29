-- SPDX-License-Identifier: MIT
-- Memory (docs/design.md 5.4). ${embedding_type} is set by ContextMigrations: public.vector(<dimensions>) when
-- pgvector is installed, real[] without it (the embedded PostgreSQL), where the nearest facts are found by an exact
-- scan instead of the HNSW index.

-- the source of truth: append-only, deletions are real (forgetting)
CREATE TABLE event_log (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  ts              timestamptz NOT NULL,                   -- when it happened (world time)
  recorded_at     timestamptz NOT NULL DEFAULT now(),
  source          text NOT NULL,                          -- conversation | brain | owner
  kind            text NOT NULL,                          -- heard | reply | sat_down | remember | edit ...
  sensitivity     text NOT NULL DEFAULT 'normal' CHECK (sensitivity IN ('normal', 'personal', 'sensitive')),
  external_ref    text,                                   -- the original's id: conversation:<id>, presence:<id>
  body            text NOT NULL DEFAULT '',
  data            jsonb NOT NULL DEFAULT '{}',
  consolidated_at timestamptz                             -- the last memory pass that read it
);
CREATE INDEX event_log_ts ON event_log (ts);
CREATE INDEX event_log_source_kind_ts ON event_log (source, kind, ts);
CREATE UNIQUE INDEX event_log_external ON event_log (source, external_ref) WHERE external_ref IS NOT NULL;
CREATE INDEX event_log_unconsolidated ON event_log (id) WHERE consolidated_at IS NULL;

-- facts, bi-temporal: valid_from/valid_to the world's time, learned_at/expired_at ours
CREATE TABLE fact (
  id            uuid PRIMARY KEY,
  subject       text NOT NULL,                            -- owner | person:<name> | place:<name> | thing:<name>
  statement     text NOT NULL,                            -- one sentence, English
  kind          text NOT NULL CHECK (kind IN ('preference', 'relation', 'plan', 'habit', 'biographical', 'state')),
  importance    smallint NOT NULL CHECK (importance BETWEEN 1 AND 10),
  confidence    real NOT NULL CHECK (confidence BETWEEN 0 AND 1),
  sensitivity   text NOT NULL CHECK (sensitivity IN ('normal', 'personal', 'sensitive')),
  valid_from    timestamptz,
  valid_to      timestamptz,
  learned_at    timestamptz NOT NULL,
  expired_at    timestamptz,
  superseded_by uuid REFERENCES fact (id) ON DELETE SET NULL,
  last_used_at  timestamptz,
  use_count     integer NOT NULL DEFAULT 0,
  archived      boolean NOT NULL DEFAULT false,
  pinned        boolean NOT NULL DEFAULT false,           -- the owner's: never decays, never rewritten
  origin        text NOT NULL CHECK (origin IN ('extracted', 'owner', 'task')),
  extracted_by  text NOT NULL DEFAULT '',                 -- model and prompt version
  embedding     ${embedding_type}                         -- NULL while the embedding model is missing
);
CREATE INDEX fact_current ON fact (subject) WHERE expired_at IS NULL;
CREATE INDEX fact_learned_at ON fact (learned_at);
CREATE INDEX fact_expired_at ON fact (expired_at) WHERE expired_at IS NOT NULL;

-- provenance: every fact keeps the events it came from
CREATE TABLE fact_source (
  fact_id  uuid NOT NULL REFERENCES fact (id) ON DELETE CASCADE,
  event_id bigint NOT NULL REFERENCES event_log (id) ON DELETE CASCADE,
  PRIMARY KEY (fact_id, event_id)
);
CREATE INDEX fact_source_event ON fact_source (event_id);

-- day, week and month summaries
CREATE TABLE episode (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  level        text NOT NULL CHECK (level IN ('day', 'week', 'month')),
  day          date NOT NULL,                             -- the period's first local day
  period_start timestamptz NOT NULL,
  period_end   timestamptz NOT NULL,
  summary      text NOT NULL,
  embedding    ${embedding_type},
  stale        boolean NOT NULL DEFAULT false,            -- something it used was forgotten: rewrite it
  created_at   timestamptz NOT NULL,
  events       integer NOT NULL DEFAULT 0,
  UNIQUE (level, period_start),
  UNIQUE (level, day)
);

-- the profile (and later the soul): every version kept
CREATE TABLE block_version (
  id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  block      text NOT NULL CHECK (block IN ('profile', 'soul_core', 'soul_learned')),
  content    text NOT NULL,
  tokens     integer NOT NULL,
  status     text NOT NULL CHECK (status IN ('proposed', 'active', 'superseded', 'rejected', 'reverted')),
  rationale  text NOT NULL DEFAULT '',
  evidence   bigint[] NOT NULL DEFAULT '{}',              -- event ids behind it
  author     text NOT NULL CHECK (author IN ('owner', 'worker', 'reflection')),
  created_at timestamptz NOT NULL,
  decided_at timestamptz,
  kept_lines text[] NOT NULL DEFAULT '{}'                 -- lines every rewrite keeps verbatim (the owner's)
);
CREATE UNIQUE INDEX block_version_active ON block_version (block) WHERE status = 'active';

-- memory's own small state: the worker's progress, backfills done, the owner's memory settings
CREATE TABLE state (
  key   text PRIMARY KEY,
  value jsonb NOT NULL
);

DO $$
BEGIN
  IF (SELECT udt_name FROM information_schema.columns
      WHERE table_schema = current_schema() AND table_name = 'fact' AND column_name = 'embedding') = 'vector' THEN
    EXECUTE 'CREATE INDEX fact_embedding ON fact USING hnsw (embedding public.vector_cosine_ops)';
    EXECUTE 'CREATE INDEX episode_embedding ON episode USING hnsw (embedding public.vector_cosine_ops)';
  END IF;
END
$$;
