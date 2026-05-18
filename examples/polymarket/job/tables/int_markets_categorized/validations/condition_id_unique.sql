SELECT condition_id, COUNT(*) AS n
FROM int_markets_categorized
GROUP BY condition_id
HAVING COUNT(*) > 1
LIMIT 5
