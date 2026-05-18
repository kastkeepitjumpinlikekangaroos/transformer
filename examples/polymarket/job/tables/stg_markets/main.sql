SELECT
  condition_id,
  question,
  CASE WHEN category IS NULL OR category = '' THEN 'unknown'
       ELSE LOWER(category) END AS category,
  end_date,
  closed,
  uma_status,
  volume,
  liquidity,
  clob_token_id_yes,
  clob_token_id_no,
  target
FROM raw_markets
WHERE condition_id IS NOT NULL
  AND volume IS NOT NULL
