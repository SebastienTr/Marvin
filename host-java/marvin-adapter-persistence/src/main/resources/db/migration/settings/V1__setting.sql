-- SPDX-License-Identifier: MIT
-- The owner's settings, one row per key (docs/design.md 5.4).
CREATE TABLE setting (
  key   text PRIMARY KEY,
  value jsonb NOT NULL
);
