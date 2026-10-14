-- SPDX-License-Identifier: MIT
-- The vital signs per minute (contract C3, GET /api/vitals): the medians of the seconds that passed the vitals
-- quality gate, and how many there were. A minute with readings but no good second has n = 0 and no rates.
-- Stamped with the host's wall clock (Unix seconds, the minute's start), as the presence history.
-- Measured, not diagnosed: what the MR60BHA2 radar reported about a still person, nothing more.
CREATE TABLE vitals_minute (
  ts     double precision PRIMARY KEY,
  heart  double precision,                        -- beats per minute, NULL when no second was good
  breath double precision,                        -- breaths per minute
  n      integer NOT NULL CHECK (n BETWEEN 0 AND 60)
);
