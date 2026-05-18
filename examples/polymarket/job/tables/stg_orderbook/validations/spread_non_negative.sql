SELECT market_id, spread
FROM stg_orderbook
WHERE spread < 0
LIMIT 5
