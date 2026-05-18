SELECT condition_id, volatility_score
FROM mart_volatility_ranking
WHERE volatility_score < 0
LIMIT 5
