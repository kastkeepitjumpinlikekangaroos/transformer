SELECT
  '{{ today }}' AS execution_date,
  tier,
  COUNT(*) AS event_count,
  SUM(amount) AS total_spend
FROM enriched_events
WHERE event = 'buy'
GROUP BY tier
ORDER BY tier
