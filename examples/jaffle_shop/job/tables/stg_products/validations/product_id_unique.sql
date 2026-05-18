SELECT product_id, COUNT(*) AS n
FROM stg_products
GROUP BY product_id
HAVING COUNT(*) > 1
