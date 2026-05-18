SELECT market_id, change_side, tick_count
FROM int_orderbook_side_breakdown
WHERE tick_count <= 0
LIMIT 5
