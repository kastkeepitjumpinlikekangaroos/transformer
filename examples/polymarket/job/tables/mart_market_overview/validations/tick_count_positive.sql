SELECT condition_id, tick_count
FROM mart_market_overview
WHERE tick_count <= 0
LIMIT 5
