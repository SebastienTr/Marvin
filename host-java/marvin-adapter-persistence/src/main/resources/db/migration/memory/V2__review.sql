-- SPDX-License-Identifier: MIT
-- Memory v1, read path: the owner's review of suggested facts (docs/design.md 5.6).

ALTER TABLE fact ADD COLUMN reviewed_at timestamptz;          -- NULL: an extracted fact the owner has not reviewed
CREATE INDEX fact_unreviewed ON fact (learned_at) WHERE reviewed_at IS NULL AND origin <> 'owner';
