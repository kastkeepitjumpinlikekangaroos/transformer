SELECT
  '{{ today }}' as exec_date,
  c.customer_id,
  c.customer_name,
  s.count_lifetime_orders,
  s.first_ordered_at,
  s.last_ordered_at,
  s.lifetime_spend_pretax,
  s.lifetime_tax_paid,
  s.lifetime_spend,
  CASE WHEN s.is_repeat_buyer THEN 'returning' ELSE 'new' END AS customer_type
FROM stg_customers c
LEFT JOIN customer_orders_summary s ON c.customer_id = s.customer_id
