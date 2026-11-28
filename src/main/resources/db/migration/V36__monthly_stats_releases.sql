-- US-32: a single immutable partition and snapshot for every released month, even when suppressed.
CREATE TABLE monthly_stats_releases (
    month_start DATE PRIMARY KEY CHECK (EXTRACT(DAY FROM month_start) = 1),
    indicator VARCHAR(50) NOT NULL CHECK (indicator IN (
        'APPOINTMENTS_PER_DAY', 'APPOINTMENTS_PER_STATUS', 'APPOINTMENTS_PER_SPECIALTY')),
    csv TEXT,
    released_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
REVOKE UPDATE, DELETE ON monthly_stats_releases FROM medlink_app;
