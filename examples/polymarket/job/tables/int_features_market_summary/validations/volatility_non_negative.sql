SELECT market_id, avg_volatility, max_volatility
FROM int_features_market_summary
WHERE avg_volatility < 0 OR max_volatility < 0
LIMIT 5
