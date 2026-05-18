SELECT customer_id, customer_type
FROM customers
WHERE customer_type NOT IN ('new', 'returning')
