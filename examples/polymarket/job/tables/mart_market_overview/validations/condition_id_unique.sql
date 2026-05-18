SELECT condition_id, COUNT(*) AS n
FROM mart_market_overview
GROUP BY condition_id
HAVING COUNT(*) > 1
LIMIT 5
