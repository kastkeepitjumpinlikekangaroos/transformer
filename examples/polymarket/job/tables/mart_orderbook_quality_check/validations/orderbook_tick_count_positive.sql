SELECT condition_id, orderbook_tick_count
FROM mart_orderbook_quality_check
WHERE orderbook_tick_count <= 0
LIMIT 5
