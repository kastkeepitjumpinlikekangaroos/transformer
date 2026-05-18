SELECT order_item_id, COUNT(*) AS n
FROM order_items
GROUP BY order_item_id
HAVING COUNT(*) > 1
