-- SPDX-License-Identifier: MIT
-- Extensions every context may use. pgvector is required with the Docker image
-- (pgvector/pgvector:pg18); the embedded PostgreSQL has no pgvector yet, so it is optional here
-- and the contexts that need it check for it (host-java/NOTES.md).
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'vector') THEN
    CREATE EXTENSION IF NOT EXISTS vector SCHEMA public;
  END IF;
END
$$;
