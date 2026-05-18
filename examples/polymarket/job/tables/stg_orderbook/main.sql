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
  -- Limit to the first ~6 hours of 2026-03-26 (midnight UTC + 21,600,000 ms)
  -- to keep the heavy single-partition orderbook scan under ~10 minutes.
  -- Remove this clause to process the full ~131M-row day.
  AND timestamp_received < 1774504800000
