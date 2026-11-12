-- Slice 001L: per-client limits on the public auth routes. Fixed-window counters shared by
-- every API instance. UNLOGGED: losing counts in a database crash only resets the limits.
-- client holds an IPv4 address or an IPv6 /64 prefix, kept at most a day (personal data).
CREATE UNLOGGED TABLE rate_limit_counters (
  bucket VARCHAR(40) NOT NULL,
  client VARCHAR(64) NOT NULL,
  window_start TIMESTAMPTZ NOT NULL,
  hits INTEGER NOT NULL,
  CONSTRAINT pk_rate_limit_counters PRIMARY KEY (bucket, client, window_start),
  CONSTRAINT ck_rate_limit_counters_hits CHECK (hits > 0)
);
CREATE INDEX ix_rate_limit_counters_window ON rate_limit_counters (window_start);
