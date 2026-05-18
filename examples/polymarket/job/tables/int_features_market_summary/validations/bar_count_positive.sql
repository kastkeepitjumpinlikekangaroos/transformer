SELECT market_id, bar_count
FROM int_features_market_summary
WHERE bar_count <= 0
LIMIT 5
