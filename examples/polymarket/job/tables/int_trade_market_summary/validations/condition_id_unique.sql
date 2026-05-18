SELECT condition_id, COUNT(*) AS n
FROM int_trade_market_summary
GROUP BY condition_id
HAVING COUNT(*) > 1
LIMIT 5
