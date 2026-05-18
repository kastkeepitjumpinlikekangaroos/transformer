SELECT
  market_id,
  COUNT(*) AS bar_count,
  AVG(close_mid) AS avg_close_mid,
  MAX(close_mid) AS max_close_mid,
  MIN(close_mid) AS min_close_mid,
  AVG(mean_spread) AS avg_mean_spread,
  AVG(bar_volatility) AS avg_volatility,
  MAX(bar_volatility) AS max_volatility,
  SUM(total_volume) AS total_volume,
  SUM(buy_volume) AS total_buy_volume,
  SUM(sell_volume) AS total_sell_volume,
  SUM(trade_count) AS total_trades,
  AVG(order_flow_imbalance) AS avg_ofi,
  AVG(depth_imbalance) AS avg_depth_imbalance,
  AVG(bid_depth) AS avg_bid_depth,
  AVG(ask_depth) AS avg_ask_depth,
  COUNT_IF(target = 1) AS target_ones,
  COUNT_IF(target = 0) AS target_zeros
FROM stg_features
GROUP BY market_id
