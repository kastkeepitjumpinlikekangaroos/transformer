SELECT
  oi.order_item_id,
  oi.order_id,
  oi.product_id,
  o.ordered_at,
  p.product_name,
  p.product_price,
  p.is_food_item,
  p.is_drink_item,
  s.supply_cost
FROM stg_order_items oi
LEFT JOIN stg_orders             o ON oi.order_id   = o.order_id
LEFT JOIN stg_products           p ON oi.product_id = p.product_id
LEFT JOIN order_supplies_summary s ON oi.product_id = s.product_id
