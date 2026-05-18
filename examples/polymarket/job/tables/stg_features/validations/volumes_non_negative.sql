SELECT market_id, total_volume, buy_volume, sell_volume
FROM stg_features
WHERE total_volume < 0 OR buy_volume < 0 OR sell_volume < 0
LIMIT 5
