SELECT
  market_id,
  COUNT(*) AS snapshot_count,
  AVG(latency_ms) AS avg_latency_ms,
  MAX(latency_ms) AS max_latency_ms,
  MIN(latency_ms) AS min_latency_ms,
  COUNT_IF(latency_ms > 100) AS slow_snapshot_count,
  COUNT_IF(latency_ms > 1000) AS very_slow_snapshot_count
FROM stg_snapshots
GROUP BY market_id
HAVING COUNT(*) > 5
