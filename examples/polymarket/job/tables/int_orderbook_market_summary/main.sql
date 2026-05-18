SELECT
  market_id,
  COUNT(*) AS tick_count,
  AVG(spread) AS avg_spread,
  MAX(spread) AS max_spread,
  MIN(spread) AS min_spread,
  AVG(mid_price) AS avg_mid_price,
  MAX(mid_price) AS max_mid_price,
  MIN(mid_price) AS min_mid_price,
  AVG(change_size) AS avg_change_size,
  SUM(change_size) AS total_change_size,
  COUNT_IF(change_side = 'BUY') AS buy_ticks,
  COUNT_IF(change_side = 'SELL') AS sell_ticks,
  AVG(latency_ms) AS avg_latency_ms,
  MAX(latency_ms) AS max_latency_ms,
  MIN(latency_ms) AS min_latency_ms,
  MIN(timestamp_received) AS first_seen_ms,
  MAX(timestamp_received) AS last_seen_ms
FROM stg_orderbook
GROUP BY market_id
HAVING COUNT(*) > 100
