SELECT condition_id, total_notional
FROM int_trade_market_summary
WHERE total_notional < 0
LIMIT 5
