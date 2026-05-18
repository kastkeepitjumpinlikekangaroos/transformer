SELECT order_id, COUNT(*) AS n
FROM stg_orders
GROUP BY order_id
HAVING COUNT(*) > 1
