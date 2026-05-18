SELECT
  condition_id,
  asset,
  UPPER(side) AS side,
  outcome,
  price,
  size,
  timestamp,
  price * size AS notional,
  CASE WHEN UPPER(side) = 'BUY' THEN 1
       WHEN UPPER(side) = 'SELL' THEN -1
       ELSE 0 END AS side_signed
FROM raw_trades
WHERE condition_id IS NOT NULL
  AND price IS NOT NULL
  AND size > 0
  AND price >= 0
  AND price <= 1
