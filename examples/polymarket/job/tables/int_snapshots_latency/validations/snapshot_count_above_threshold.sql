SELECT market_id, snapshot_count
FROM int_snapshots_latency
WHERE snapshot_count <= 5
LIMIT 5
