SELECT a.order_item_id, a.order_id
FROM order_items a
LEFT JOIN stg_orders b ON a.order_id = b.order_id
WHERE a.order_id IS NOT NULL AND b.order_id IS NULL
