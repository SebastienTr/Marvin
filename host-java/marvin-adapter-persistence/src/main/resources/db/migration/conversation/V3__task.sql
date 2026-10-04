-- SPDX-License-Identifier: MIT
-- Background tasks (docs/intelligence.md, "Thinking and background tasks"; docs/design.md 6.2): one row per task,
-- one row per step it took (a request to the model, a tool run, a note), kept so the app shows exactly what it did and a
-- task survives a restart (as interrupted). Times are Unix seconds, as in entry.
CREATE TABLE task (
  id           text PRIMARY KEY,
  goal         text NOT NULL,
  title        text NOT NULL,
  language     text NOT NULL,
  origin       text NOT NULL,
  status       text NOT NULL,
  created_at   double precision NOT NULL,
  started_at   double precision,
  finished_at  double precision,
  model        text NOT NULL DEFAULT '',
  reasoning    text NOT NULL DEFAULT '',
  think        text NOT NULL DEFAULT '',
  budget       json NOT NULL DEFAULT '{}',
  spent        json NOT NULL DEFAULT '{}',
  summary      text NOT NULL DEFAULT '',
  result       text NOT NULL DEFAULT '',
  error        text NOT NULL DEFAULT '',
  announced    text NOT NULL DEFAULT '',
  announced_at double precision
);
CREATE INDEX task_created ON task (created_at);
CREATE INDEX task_open ON task (status) WHERE status IN ('queued', 'running');

CREATE TABLE task_step (
  task_id text NOT NULL REFERENCES task ON DELETE CASCADE,
  n       integer NOT NULL,
  at      double precision NOT NULL,
  kind    text NOT NULL,
  data    json NOT NULL DEFAULT '{}',
  PRIMARY KEY (task_id, n)
);
