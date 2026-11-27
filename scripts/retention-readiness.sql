-- Read-only inventory for a supervised retention review. No destruction is authorized by this report.
-- Run only on an authorized administrative connection; UUIDs are personal data. Do not publish the output.
-- Supply retention_years using psql -v retention_years=20 (must match the application; minimum 20).
BEGIN TRANSACTION READ ONLY;
SET LOCAL TIME ZONE 'UTC';
WITH evidence AS (
    SELECT d.patient_id, d.decided_at, d.account_closed_at, p.birth_date,
        GREATEST(
            (SELECT max(s.starts_at) FROM appointments a JOIN slots s ON s.id = a.slot_id
             WHERE a.patient_id = p.id AND a.status = 'BOOKED' AND s.starts_at <= now()),
            (SELECT max(r.created_at) FROM consultation_reports r WHERE r.patient_id = p.id),
            (SELECT max(e.created_at) FROM exam_results e WHERE e.patient_id = p.id),
            (SELECT max(f.created_at) FROM patient_documents f WHERE f.patient_id = p.id)
        ) AS last_activity,
        EXISTS (SELECT 1 FROM appointments a JOIN slots s ON s.id = a.slot_id
                WHERE a.patient_id = p.id AND a.status = 'BOOKED' AND s.ends_at > now()) AS active_bookings,
        EXISTS (SELECT 1 FROM account_operations o WHERE o.user_id = p.id AND o.status = 'PENDING') AS pending_identity,
        EXISTS (SELECT 1 FROM patient_email_changes c WHERE c.patient_id = p.id AND c.verified_at IS NOT NULL) AS pending_email
    FROM deletion_requests d JOIN patients p ON p.id = d.patient_id WHERE d.status = 'APPROVED'
), retention AS (
    SELECT *, CASE WHEN last_activity IS NULL THEN NULL ELSE
        GREATEST(last_activity::date + make_interval(years => greatest(20, :retention_years)),
            CASE WHEN birth_date + interval '18 years' > last_activity::date
                 THEN birth_date + interval '28 years' END)::date END AS retained_until
    FROM evidence
)
SELECT patient_id, decided_at, account_closed_at, retained_until,
    CASE WHEN retained_until > current_date THEN 'RETENTION_APPLIES'
         WHEN account_closed_at IS NULL OR pending_identity OR pending_email THEN 'IDENTITY_REVIEW_REQUIRED'
         WHEN active_bookings THEN 'ACTIVE_APPOINTMENTS'
         ELSE 'LEGAL_SUPERVISION_REQUIRED' END AS disposition
FROM retention ORDER BY decided_at, patient_id;
COMMIT;
