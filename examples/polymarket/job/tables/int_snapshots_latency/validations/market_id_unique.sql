SELECT market_id, COUNT(*) AS n
FROM int_snapshots_latency
GROUP BY market_id
HAVING COUNT(*) > 1
LIMIT 5
