SELECT market_id, update_type
FROM stg_snapshots
WHERE update_type = ''
   OR update_type IS NULL
LIMIT 5
