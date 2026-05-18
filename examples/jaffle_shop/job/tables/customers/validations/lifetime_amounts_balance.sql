SELECT customer_id, lifetime_spend_pretax, lifetime_tax_paid, lifetime_spend
FROM customers
WHERE ABS(lifetime_spend_pretax + lifetime_tax_paid - lifetime_spend) > 0.01
