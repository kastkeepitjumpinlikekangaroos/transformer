SELECT order_item_id, COUNT(*) AS n
FROM stg_order_items
GROUP BY order_item_id
HAVING COUNT(*) > 1
