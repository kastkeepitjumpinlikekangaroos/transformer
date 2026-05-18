SELECT
  ob.market_id AS condition_id,
  ob.tick_count AS orderbook_tick_count,
  ob.avg_latency_ms AS orderbook_avg_latency_ms,
  ob.max_latency_ms AS orderbook_max_latency_ms,
  sn.snapshot_count,
  sn.avg_latency_ms AS snapshot_avg_latency_ms,
  sn.max_latency_ms AS snapshot_max_latency_ms,
  sn.slow_snapshot_count,
  sn.very_slow_snapshot_count,
  CASE WHEN sn.max_latency_ms > 1000 THEN 'high'
       WHEN sn.max_latency_ms > 100 THEN 'medium'
       ELSE 'low' END AS latency_tier
FROM int_orderbook_market_summary ob
INNER JOIN int_snapshots_latency sn ON ob.market_id = sn.market_id
