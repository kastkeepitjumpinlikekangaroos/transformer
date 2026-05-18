SELECT
  mo.condition_id,
  mo.question,
  mo.category_normalized,
  mo.tick_count,
  mo.trade_count,
  mo.market_volume,
  mo.market_liquidity,
  mo.avg_spread,
  mo.avg_latency_ms,
  fs.bar_count,
  fs.avg_volatility,
  fs.total_volume AS feature_total_volume,
  fs.avg_ofi,
  fs.avg_depth_imbalance,
  fs.target_ones,
  fs.target_zeros,
  mo.tick_count + COALESCE(fs.total_trades, 0) AS activity_score
FROM mart_market_overview mo
LEFT JOIN int_features_market_summary fs ON mo.condition_id = fs.market_id
WHERE mo.tick_count > 1000
ORDER BY mo.tick_count + COALESCE(fs.total_trades, 0) DESC
LIMIT 500
