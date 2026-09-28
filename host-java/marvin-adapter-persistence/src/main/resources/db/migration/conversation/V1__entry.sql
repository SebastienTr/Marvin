-- SPDX-License-Identifier: MIT
-- The conversation with Marvin (the Python host's store.py, schema 2): one row per line of the Talk
-- panel. id: milliseconds, growing across restarts; data: the rest of the entry, keys in order.
CREATE TABLE entry (
  id   bigint PRIMARY KEY,
  ts   double precision NOT NULL,
  kind text NOT NULL,
  text text NOT NULL DEFAULT '',
  data json NOT NULL DEFAULT '{}'
);
CREATE INDEX entry_ts ON entry (ts);
