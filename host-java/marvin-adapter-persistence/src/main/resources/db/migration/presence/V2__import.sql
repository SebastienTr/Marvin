-- SPDX-License-Identifier: MIT
-- This context's share of an import of the Python host's database (SqliteImporter): each context imports
-- its own tables in its own transaction and records it here, so an import never spans two schemas.
CREATE TABLE import (
  source       text PRIMARY KEY,
  imported_at  timestamptz NOT NULL DEFAULT now(),
  rows         integer NOT NULL
);
