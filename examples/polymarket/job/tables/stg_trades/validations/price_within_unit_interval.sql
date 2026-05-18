SELECT condition_id, price, size
FROM stg_trades
WHERE price < 0 OR price > 1
LIMIT 5
