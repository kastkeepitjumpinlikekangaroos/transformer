SELECT condition_id, tick_count
FROM mart_high_activity_markets
WHERE tick_count <= 1000
LIMIT 5
