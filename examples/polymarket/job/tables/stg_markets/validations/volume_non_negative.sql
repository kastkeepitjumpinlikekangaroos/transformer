SELECT condition_id, volume
FROM stg_markets
WHERE volume < 0
LIMIT 5
