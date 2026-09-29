-- SPDX-License-Identifier: MIT
-- Memory v1, review fixes: the sources of a forgotten fact are withheld (never summarised, recalled, listed or
-- exported again) while other facts keep their link to them.

ALTER TABLE event_log ADD COLUMN withheld boolean NOT NULL DEFAULT false;
