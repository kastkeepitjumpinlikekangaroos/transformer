SELECT condition_id, trade_count
FROM mart_market_overview
WHERE trade_count < 0
LIMIT 5
