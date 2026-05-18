SELECT customer_id, COUNT(*) AS n
FROM stg_customers
GROUP BY customer_id
HAVING COUNT(*) > 1
