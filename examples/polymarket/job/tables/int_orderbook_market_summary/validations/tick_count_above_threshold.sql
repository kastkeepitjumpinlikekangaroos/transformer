SELECT market_id, tick_count
FROM int_orderbook_market_summary
WHERE tick_count <= 100
LIMIT 5
