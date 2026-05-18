SELECT category, total_ticks
FROM final_combined_report
WHERE total_ticks < 0
LIMIT 5
