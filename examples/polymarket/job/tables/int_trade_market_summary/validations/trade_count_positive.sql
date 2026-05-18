SELECT condition_id, trade_count
FROM int_trade_market_summary
WHERE trade_count <= 0
LIMIT 5
