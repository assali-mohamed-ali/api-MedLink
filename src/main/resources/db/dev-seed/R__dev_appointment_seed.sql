-- Dev data for the appointment epic (MLink-97). Loaded by the dev profile only (application-dev.yml),
-- never in tests, staging or prod. Safe to run again: every insert skips what is already there.
-- The three practitioners are the realm's dev practitioners (keycloak/realm-medlink.json): same ids and
-- emails, so they log in onto these rows. Slots cover the next two weeks from the day this runs;
-- run scripts/setup-dev with --reset to get fresh ones.

INSERT INTO clinics (id, name, city) VALUES
    ('c1000000-0000-4000-8000-000000000001', 'Clinique MedLink Sousse', 'Sousse'),
    ('c1000000-0000-4000-8000-000000000002', 'Clinique MedLink Sfax', 'Sfax'),
    ('c1000000-0000-4000-8000-000000000003', 'Clinique MedLink Tunis', 'Tunis')
ON CONFLICT (id) DO NOTHING;

INSERT INTO specialties (id, name) VALUES
    ('5e000000-0000-4000-8000-000000000001', 'Médecine générale'),
    ('5e000000-0000-4000-8000-000000000002', 'Cardiologie'),
    ('5e000000-0000-4000-8000-000000000003', 'Pédiatrie')
ON CONFLICT DO NOTHING;

-- A first login may already have created the users row (same id): then it is kept as it is.
INSERT INTO users (id, username, email) VALUES
    ('d0c70000-0000-4000-8000-000000000001', 'generalist.dev', 'generalist.dev@medlink.local'),
    ('d0c70000-0000-4000-8000-000000000002', 'cardiologist.dev', 'cardiologist.dev@medlink.local'),
    ('d0c70000-0000-4000-8000-000000000003', 'pediatrician.dev', 'pediatrician.dev@medlink.local')
ON CONFLICT DO NOTHING;

INSERT INTO practitioners (id, first_name, last_name, specialty_id) VALUES
    ('d0c70000-0000-4000-8000-000000000001', 'Généraliste', 'Dev', '5e000000-0000-4000-8000-000000000001'),
    ('d0c70000-0000-4000-8000-000000000002', 'Cardiologue', 'Dev', '5e000000-0000-4000-8000-000000000002'),
    ('d0c70000-0000-4000-8000-000000000003', 'Pédiatre', 'Dev', '5e000000-0000-4000-8000-000000000003')
ON CONFLICT (id) DO NOTHING;

-- Each practitioner works in two clinics.
INSERT INTO practitioner_clinics (practitioner_id, clinic_id) VALUES
    ('d0c70000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000001'),
    ('d0c70000-0000-4000-8000-000000000001', 'c1000000-0000-4000-8000-000000000003'),
    ('d0c70000-0000-4000-8000-000000000002', 'c1000000-0000-4000-8000-000000000002'),
    ('d0c70000-0000-4000-8000-000000000002', 'c1000000-0000-4000-8000-000000000003'),
    ('d0c70000-0000-4000-8000-000000000003', 'c1000000-0000-4000-8000-000000000001'),
    ('d0c70000-0000-4000-8000-000000000003', 'c1000000-0000-4000-8000-000000000002')
ON CONFLICT DO NOTHING;

-- Free 30-minute slots, Monday to Friday, 08:00-12:00 and 13:00-17:00 Tunis time, for the next 14 days.
-- Each practitioner is in one of their two clinics per day (alternating by day of the month).
INSERT INTO slots (id, practitioner_id, clinic_id, starts_at, ends_at, status)
SELECT gen_random_uuid(),
       p.practitioner_id,
       CASE WHEN extract(day FROM d.day)::int % 2 = 0 THEN p.clinic_a ELSE p.clinic_b END,
       (d.day::date + t.local_start) AT TIME ZONE 'Africa/Tunis',
       (d.day::date + t.local_start + interval '30 minutes') AT TIME ZONE 'Africa/Tunis',
       'FREE'
FROM (VALUES
        ('d0c70000-0000-4000-8000-000000000001'::uuid,
         'c1000000-0000-4000-8000-000000000001'::uuid, 'c1000000-0000-4000-8000-000000000003'::uuid),
        ('d0c70000-0000-4000-8000-000000000002'::uuid,
         'c1000000-0000-4000-8000-000000000002'::uuid, 'c1000000-0000-4000-8000-000000000003'::uuid),
        ('d0c70000-0000-4000-8000-000000000003'::uuid,
         'c1000000-0000-4000-8000-000000000001'::uuid, 'c1000000-0000-4000-8000-000000000002'::uuid)
     ) AS p (practitioner_id, clinic_a, clinic_b)
CROSS JOIN generate_series(current_date + 1, current_date + 14, interval '1 day') AS d (day)
CROSS JOIN (
        SELECT time '08:00' + n * interval '30 minutes' AS local_start FROM generate_series(0, 7) AS n
        UNION ALL
        SELECT time '13:00' + n * interval '30 minutes' FROM generate_series(0, 7) AS n
     ) AS t
WHERE extract(isodow FROM d.day) < 6
ON CONFLICT (practitioner_id, starts_at) DO NOTHING;
