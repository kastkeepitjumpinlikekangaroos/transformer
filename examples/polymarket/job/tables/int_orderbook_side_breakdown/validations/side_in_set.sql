SELECT market_id, change_side
FROM int_orderbook_side_breakdown
WHERE change_side NOT IN ('BUY', 'SELL')
LIMIT 5
