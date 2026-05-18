SELECT
  condition_id,
  question,
  category,
  CASE
    WHEN category IN ('politics', 'us-politics', 'world-politics') THEN 'politics'
    WHEN category IN ('crypto', 'cryptocurrency') THEN 'crypto'
    WHEN category IN ('sports', 'sport') THEN 'sports'
    WHEN category IN ('entertainment', 'pop-culture') THEN 'entertainment'
    WHEN category = 'unknown' THEN 'unknown'
    ELSE 'other'
  END AS category_normalized,
  end_date,
  closed,
  volume,
  liquidity,
  target,
  CASE WHEN volume > 100000 THEN TRUE ELSE FALSE END AS is_high_volume
FROM stg_markets
