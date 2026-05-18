SELECT market_id, avg_spread
FROM int_orderbook_market_summary
WHERE avg_spread < 0
LIMIT 5
