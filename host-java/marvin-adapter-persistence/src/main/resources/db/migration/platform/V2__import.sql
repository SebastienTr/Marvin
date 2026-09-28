-- SPDX-License-Identifier: MIT
-- Databases of the Python host imported once (SqliteImporter): a source is never imported twice.
CREATE TABLE import (
  source       text PRIMARY KEY,
  size_bytes   bigint NOT NULL,
  imported_at  timestamptz NOT NULL DEFAULT now(),
  events       integer NOT NULL,
  samples      integer NOT NULL,
  conversation integer NOT NULL,
  settings     integer NOT NULL
);
