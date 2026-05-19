SELECT
  market_id,
  change_side,
  change_price,
  change_size,
  spread,
  mid_price,
  timestamp_received,
  timestamp_created_at - timestamp_received AS latency_ms
FROM raw_orderbook
WHERE market_id IS NOT NULL
  AND timestamp_received IS NOT NULL
  AND best_bid IS NOT NULL
  AND best_ask IS NOT NULL
  AND best_bid >= 0 AND best_bid <= 1
  AND best_ask >= 0 AND best_ask <= 1
  AND change_size >= 0
  -- Limit to the last ~18 hours of 2026-03-26.
  -- With parquet predicate pushdown, this also lets the reader skip whole
  -- row groups whose `timestamp_received` min/max is past the threshold —
  -- so on a 21-day glob, 20 days' files get eliminated at the stats level
  -- and never have their column data read.
  AND timestamp_received > 1774504800000
