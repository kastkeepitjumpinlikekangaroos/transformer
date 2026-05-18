SELECT condition_id, category
FROM stg_markets
WHERE category IS NULL
LIMIT 5
