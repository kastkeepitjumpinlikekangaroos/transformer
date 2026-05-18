SELECT
  market_id,
  change_side,
  COUNT(*) AS tick_count,
  AVG(change_size) AS avg_size,
  SUM(change_size) AS total_size,
  AVG(change_price) AS avg_price,
  MIN(change_price) AS min_price,
  MAX(change_price) AS max_price
FROM stg_orderbook
GROUP BY market_id, change_side
HAVING COUNT(*) > 50
