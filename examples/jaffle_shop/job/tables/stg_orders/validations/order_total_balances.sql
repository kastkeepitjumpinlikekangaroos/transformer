SELECT order_id, subtotal_cents, tax_paid_cents, order_total_cents
FROM stg_orders
WHERE order_total_cents - tax_paid_cents <> subtotal_cents
