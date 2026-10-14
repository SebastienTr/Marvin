-- SPDX-License-Identifier: MIT
-- The owner's approvals (docs/design.md 6.3): one row per call of a tool that writes outside this computer that a
-- background task asked to make. State: pending, approved, rejected, expired. The preview is what the owner was shown
-- (for an email draft: to, subject, body, and the draft's link once made); the arguments are what ran once approved.
-- Times are Unix seconds, as in task.
CREATE TABLE approval (
  id        bigserial PRIMARY KEY,
  task_id   text NOT NULL REFERENCES task ON DELETE CASCADE,
  tool      text NOT NULL,
  access    text NOT NULL DEFAULT 'EXTERNAL_WRITE' CHECK (access = 'EXTERNAL_WRITE'),
  state     text NOT NULL CHECK (state IN ('pending', 'approved', 'rejected', 'expired')),
  created   double precision NOT NULL,
  decided   double precision,
  summary   text NOT NULL DEFAULT '',
  preview   json NOT NULL DEFAULT '{}',
  arguments json NOT NULL DEFAULT '{}',
  reason    text NOT NULL DEFAULT '',
  CHECK ((state = 'pending') = (decided IS NULL))
);
CREATE INDEX approval_pending ON approval (id) WHERE state = 'pending';
CREATE INDEX approval_task ON approval (task_id);

-- a task waiting for an approval is open, as a queued or running one
DROP INDEX task_open;
CREATE INDEX task_open ON task (status) WHERE status IN ('queued', 'running', 'needs_approval');
