SELECT
  o.order_id,
  o.location_id,
  o.customer_id,
  o.subtotal_cents,
  o.tax_paid_cents,
  o.order_total_cents,
  o.subtotal,
  o.tax_paid,
  o.order_total,
  o.ordered_at,
  s.order_cost,
  s.order_items_subtotal,
  s.count_food_items,
  s.count_drink_items,
  s.count_order_items,
  s.count_food_items  > 0 AS is_food_order,
  s.count_drink_items > 0 AS is_drink_order
FROM stg_orders o
LEFT JOIN order_items_summary s ON o.order_id = s.order_id
