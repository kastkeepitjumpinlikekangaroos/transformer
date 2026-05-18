SELECT market_id, timestamp_received, timestamp_created_at, latency_ms
FROM stg_snapshots
WHERE latency_ms < -86400000 OR latency_ms > 86400000
LIMIT 5
