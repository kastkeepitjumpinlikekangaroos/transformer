SELECT
  latency_tier,
  COUNT(*) AS market_count,
  AVG(orderbook_tick_count) AS avg_orderbook_tick_count,
  AVG(snapshot_count) AS avg_snapshot_count,
  AVG(snapshot_avg_latency_ms) AS avg_snapshot_latency,
  MAX(snapshot_max_latency_ms) AS max_observed_latency,
  SUM(slow_snapshot_count) AS total_slow_snapshots,
  SUM(very_slow_snapshot_count) AS total_very_slow_snapshots
FROM mart_orderbook_quality_check
GROUP BY latency_tier
ORDER BY market_count DESC
