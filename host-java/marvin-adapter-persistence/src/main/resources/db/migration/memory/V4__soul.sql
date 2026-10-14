-- SPDX-License-Identifier: MIT
-- Marvin's soul (docs/design.md 8): every version of its two parts (the core, written by the owner only; the learned
-- part, proposed by the weekly reflection and approved by the owner, or edited by them), the reflection's proposals with
-- their diff, the events behind them and the guardrails' verdicts, and when the reflection last ran.
CREATE TABLE soul_version (
  part     text NOT NULL CHECK (part IN ('core', 'learned')),
  version  integer NOT NULL CHECK (version > 0),
  text     text NOT NULL,
  created  timestamptz NOT NULL,
  author   text NOT NULL CHECK (author IN ('owner', 'reflection')),
  note     text,
  PRIMARY KEY (part, version),
  CHECK (part = 'learned' OR author = 'owner')            -- the core is the owner's only
);
CREATE INDEX soul_version_created ON soul_version (created DESC);

CREATE TABLE soul_proposal (
  id           bigserial PRIMARY KEY,
  created      timestamptz NOT NULL,
  state        text NOT NULL CHECK (state IN ('pending', 'approved', 'rejected', 'superseded')),
  text         text NOT NULL,
  rationale    text NOT NULL DEFAULT '',
  diff         jsonb NOT NULL DEFAULT '[]',
  events       jsonb NOT NULL DEFAULT '[]',               -- event_log ids behind it (no foreign key: forgetting stays free)
  guardrails   jsonb NOT NULL DEFAULT '[]',
  base_version integer NOT NULL DEFAULT 0,
  decided      timestamptz                                -- the owner's decision (or dismissal); null until then
);
CREATE INDEX soul_proposal_open ON soul_proposal (id) WHERE decided IS NULL AND state IN ('pending', 'rejected');

CREATE TABLE soul_state (
  key   text PRIMARY KEY,
  value text NOT NULL
);
