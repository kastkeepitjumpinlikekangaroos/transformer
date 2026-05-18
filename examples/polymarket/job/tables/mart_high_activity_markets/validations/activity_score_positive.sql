SELECT condition_id, activity_score
FROM mart_high_activity_markets
WHERE activity_score <= 0
LIMIT 5
