SELECT
  customer_id,
  COUNT(DISTINCT order_id)     AS count_lifetime_orders,
  COUNT(DISTINCT order_id) > 1 AS is_repeat_buyer,
  MIN(ordered_at)              AS first_ordered_at,
  MAX(ordered_at)              AS last_ordered_at,
  SUM(subtotal)                AS lifetime_spend_pretax,
  SUM(tax_paid)                AS lifetime_tax_paid,
  SUM(order_total)             AS lifetime_spend
FROM orders
GROUP BY customer_id
