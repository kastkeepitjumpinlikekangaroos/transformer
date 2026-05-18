SELECT condition_id, side, price, size
FROM stg_trades
WHERE size <= 0
LIMIT 5
