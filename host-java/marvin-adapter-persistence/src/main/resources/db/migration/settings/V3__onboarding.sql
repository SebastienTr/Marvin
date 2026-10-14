-- SPDX-License-Identifier: MIT
-- The first-run setup: what only the owner can say, one row per step (docs/ui.md "Setting Marvin up"):
-- a step skipped, the speaker heard, the quiet hours answered. The checks themselves are never stored.
CREATE TABLE onboarding (
  step       text PRIMARY KEY,
  state      jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
