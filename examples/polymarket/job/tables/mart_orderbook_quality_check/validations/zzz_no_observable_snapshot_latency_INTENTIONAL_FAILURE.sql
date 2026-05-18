-- This validation is INTENTIONALLY constructed to fail on real Polymarket data.
-- The assertion "no market has observable snapshot latency" (snapshot_max_latency_ms > 0)
-- is false on any real-world feed: every market has at least one snapshot whose
-- created_at differs from received timestamp by more than 0ms. Returning any rows
-- marks mart_orderbook_quality_check as ValidationFailed, which causes the
-- downstream task mart_quality_report (which selects FROM mart_orderbook_quality_check)
-- to be SKIPPED by the runner. Other mart branches (overview, high-activity,
-- volatility) are unaffected and run to completion.
SELECT condition_id, snapshot_max_latency_ms
FROM mart_orderbook_quality_check
WHERE snapshot_max_latency_ms > 0
LIMIT 10
