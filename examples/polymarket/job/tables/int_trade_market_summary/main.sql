SELECT
  condition_id,
  COUNT(*) AS trade_count,
  COUNT_IF(side = 'BUY') AS buy_count,
  COUNT_IF(side = 'SELL') AS sell_count,
  SUM(size) AS total_size,
  SUM(notional) AS total_notional,
  AVG(price) AS avg_price,
  MAX(price) AS max_price,
  MIN(price) AS min_price,
  COUNT_IF(outcome = 'Yes') AS yes_trades,
  COUNT_IF(outcome = 'No') AS no_trades,
  MIN(timestamp) AS first_trade_ts,
  MAX(timestamp) AS last_trade_ts
FROM stg_trades
GROUP BY condition_id
HAVING COUNT(*) > 1
