SELECT market_id, close_mid
FROM stg_features
WHERE close_mid < 0 OR close_mid > 1
LIMIT 5
