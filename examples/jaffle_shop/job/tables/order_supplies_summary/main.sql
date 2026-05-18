SELECT
  product_id,
  SUM(supply_cost) AS supply_cost
FROM stg_supplies
GROUP BY product_id
