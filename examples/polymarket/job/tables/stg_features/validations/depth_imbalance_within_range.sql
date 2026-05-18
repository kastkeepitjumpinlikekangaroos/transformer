SELECT market_id, bid_depth, ask_depth, depth_imbalance
FROM stg_features
WHERE depth_imbalance < -1.001 OR depth_imbalance > 1.001
LIMIT 5
