SELECT latency_tier
FROM mart_quality_report
WHERE latency_tier NOT IN ('high', 'medium', 'low')
LIMIT 5
