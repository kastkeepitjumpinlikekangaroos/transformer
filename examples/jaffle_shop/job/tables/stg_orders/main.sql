SELECT
  id          AS order_id,
  store_id    AS location_id,
  customer    AS customer_id,
  subtotal    AS subtotal_cents,
  tax_paid    AS tax_paid_cents,
  order_total AS order_total_cents,
  CAST(subtotal    AS DOUBLE) / 100 AS subtotal,
  CAST(tax_paid    AS DOUBLE) / 100 AS tax_paid,
  CAST(order_total AS DOUBLE) / 100 AS order_total,
  ordered_at
FROM raw_orders
