SELECT supply_uuid, COUNT(*) AS n
FROM stg_supplies
GROUP BY supply_uuid
HAVING COUNT(*) > 1
