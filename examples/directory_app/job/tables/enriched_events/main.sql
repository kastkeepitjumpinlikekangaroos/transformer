SELECT
  '{{ iso_timestamp }}' AS execution_time,
  e.user_id,
  u.name,
  u.tier,
  e.event,
  e.amount
FROM events e
LEFT JOIN users u ON e.user_id = u.user_id
