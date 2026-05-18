SELECT location_id, COUNT(*) AS n
FROM stg_locations
GROUP BY location_id
HAVING COUNT(*) > 1
