SELECT
  id || '-' || sku AS supply_uuid,
  id   AS supply_id,
  sku  AS product_id,
  name AS supply_name,
  CAST(cost AS DOUBLE) / 100 AS supply_cost,
  perishable AS is_perishable_supply
FROM raw_supplies
