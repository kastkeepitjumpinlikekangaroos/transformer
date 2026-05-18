SELECT
  market_id,
  minute_bar,
  close_mid,
  mean_spread,
  close_spread,
  bar_volatility,
  total_volume,
  buy_volume,
  sell_volume,
  trade_count,
  order_flow_imbalance,
  target,
  return_1m,
  bid_depth,
  ask_depth,
  depth_imbalance
FROM raw_features
WHERE market_id IS NOT NULL
  AND close_mid IS NOT NULL
