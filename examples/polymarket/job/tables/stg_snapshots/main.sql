SELECT
  market_id,
  update_type,
  timestamp_received,
  timestamp_created_at,
  timestamp_created_at - timestamp_received AS latency_ms
FROM raw_snapshots
WHERE market_id IS NOT NULL
  AND timestamp_received IS NOT NULL
  AND update_type IS NOT NULL
