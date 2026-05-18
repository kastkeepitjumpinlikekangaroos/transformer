SELECT
  ob.market_id AS condition_id,
  mc.question,
  mc.category_normalized,
  mc.volume AS market_volume,
  mc.liquidity AS market_liquidity,
  mc.is_high_volume,
  ob.tick_count,
  ob.avg_spread,
  ob.avg_mid_price,
  ob.max_spread,
  ob.total_change_size,
  ob.buy_ticks,
  ob.sell_ticks,
  ob.avg_latency_ms,
  COALESCE(tr.trade_count, 0) AS trade_count,
  COALESCE(tr.total_size, 0.0) AS trade_total_size,
  COALESCE(tr.total_notional, 0.0) AS trade_total_notional,
  COALESCE(tr.avg_price, 0.0) AS trade_avg_price
FROM int_orderbook_market_summary ob
LEFT JOIN int_markets_categorized mc ON ob.market_id = mc.condition_id
LEFT JOIN int_trade_market_summary tr ON ob.market_id = tr.condition_id
