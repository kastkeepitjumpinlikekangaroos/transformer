SELECT condition_id, COUNT(*) AS n
FROM mart_orderbook_quality_check
GROUP BY condition_id
HAVING COUNT(*) > 1
LIMIT 5
