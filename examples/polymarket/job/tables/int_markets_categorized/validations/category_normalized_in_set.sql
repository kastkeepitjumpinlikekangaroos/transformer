SELECT condition_id, category, category_normalized
FROM int_markets_categorized
WHERE category_normalized NOT IN ('politics', 'crypto', 'sports', 'entertainment', 'unknown', 'other')
LIMIT 5
