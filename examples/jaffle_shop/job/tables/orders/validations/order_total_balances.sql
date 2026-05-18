SELECT order_id, subtotal_cents, tax_paid_cents, order_total_cents
FROM orders
WHERE subtotal_cents + tax_paid_cents <> order_total_cents
