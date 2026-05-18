SELECT latency_tier, market_count
FROM mart_quality_report
WHERE market_count <= 0
LIMIT 5
