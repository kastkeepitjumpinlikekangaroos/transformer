SELECT a.order_id, a.customer_id
FROM orders a
LEFT JOIN stg_customers b ON a.customer_id = b.customer_id
WHERE a.customer_id IS NOT NULL AND b.customer_id IS NULL
