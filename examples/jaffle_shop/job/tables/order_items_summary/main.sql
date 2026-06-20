WITH items AS (
  SELECT
    order_id,
    supply_cost,
    product_price,
    order_item_id,
    is_food_item,
    is_drink_item
  FROM order_items
)
SELECT
  order_id,
  SUM(supply_cost)                                 AS order_cost,
  SUM(product_price)                               AS order_items_subtotal,
  COUNT(order_item_id)                             AS count_order_items,
  SUM(CASE WHEN is_food_item  THEN 1 ELSE 0 END)   AS count_food_items,
  SUM(CASE WHEN is_drink_item THEN 1 ELSE 0 END)   AS count_drink_items
FROM items
GROUP BY order_id
