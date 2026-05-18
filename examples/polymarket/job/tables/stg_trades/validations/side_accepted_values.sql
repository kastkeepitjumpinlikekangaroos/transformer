SELECT condition_id, side
FROM stg_trades
WHERE side NOT IN ('BUY', 'SELL')
LIMIT 5
