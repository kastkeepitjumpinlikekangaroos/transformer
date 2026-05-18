SELECT category, market_count
FROM final_combined_report
WHERE market_count <= 0
LIMIT 5
