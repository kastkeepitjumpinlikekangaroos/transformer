SELECT
  ob.market_id AS condition_id,
  ob.tick_count,
  ob.avg_spread,
  ob.max_spread,
  fs.avg_volatility,
  fs.max_volatility,
  fs.total_volume,
  ob.avg_spread * 100.0 + COALESCE(fs.avg_volatility, 0.0) * 1000.0 AS volatility_score
FROM int_orderbook_market_summary ob
LEFT JOIN int_features_market_summary fs ON ob.market_id = fs.market_id
WHERE ob.tick_count > 500
ORDER BY ob.avg_spread * 100.0 + COALESCE(fs.avg_volatility, 0.0) * 1000.0 DESC
LIMIT 200
