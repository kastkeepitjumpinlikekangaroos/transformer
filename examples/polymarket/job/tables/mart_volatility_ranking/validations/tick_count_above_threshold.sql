SELECT condition_id, tick_count
FROM mart_volatility_ranking
WHERE tick_count <= 500
LIMIT 5
