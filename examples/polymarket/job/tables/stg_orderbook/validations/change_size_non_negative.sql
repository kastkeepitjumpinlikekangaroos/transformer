SELECT market_id, change_size
FROM stg_orderbook
WHERE change_size < 0
LIMIT 5
