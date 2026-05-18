SELECT
  mo.category_normalized AS category,
  COUNT(*) AS market_count,
  SUM(mo.tick_count) AS total_ticks,
  SUM(mo.trade_count) AS total_trades,
  SUM(mo.market_volume) AS total_market_volume,
  AVG(mo.avg_spread) AS avg_spread,
  AVG(mo.avg_latency_ms) AS avg_orderbook_latency_ms,
  AVG(vr.volatility_score) AS avg_volatility_score,
  COUNT(ha.activity_score) AS high_activity_count
FROM mart_market_overview mo
LEFT JOIN mart_volatility_ranking vr ON mo.condition_id = vr.condition_id
LEFT JOIN mart_high_activity_markets ha ON mo.condition_id = ha.condition_id
GROUP BY mo.category_normalized
ORDER BY SUM(mo.tick_count) DESC
