SELECT
  sku         AS product_id,
  name        AS product_name,
  type        AS product_type,
  description AS product_description,
  CAST(price AS DOUBLE) / 100 AS product_price,
  type = 'jaffle'   AS is_food_item,
  type = 'beverage' AS is_drink_item
FROM raw_products
