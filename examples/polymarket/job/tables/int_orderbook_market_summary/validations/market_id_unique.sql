SELECT market_id, COUNT(*) AS n
FROM int_orderbook_market_summary
GROUP BY market_id
HAVING COUNT(*) > 1
LIMIT 5
