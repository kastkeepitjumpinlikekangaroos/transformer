SELECT condition_id, COUNT(*) AS n
FROM stg_markets
GROUP BY condition_id
HAVING COUNT(*) > 1
LIMIT 5
