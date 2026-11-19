-- Reference specialties (MLink-174), in every environment, like the clinics of V15: a practitioner needs one
-- (practitioners.specialty_id NOT NULL) and no screen or endpoint creates a specialty, so without them an admin
-- could not create a practitioner in staging or production. The usual consultation specialties of a multi-site
-- clinic. The first three keep the ids of the dev seed (db/dev-seed), whose insert skips them with ON CONFLICT, so
-- dev keeps working. A specialty added later goes in a new migration.

INSERT INTO specialties (id, name) VALUES
    ('5e000000-0000-4000-8000-000000000001', 'Médecine générale'),
    ('5e000000-0000-4000-8000-000000000002', 'Cardiologie'),
    ('5e000000-0000-4000-8000-000000000003', 'Pédiatrie'),
    ('5e000000-0000-4000-8000-000000000004', 'Dermatologie'),
    ('5e000000-0000-4000-8000-000000000005', 'Gynécologie-obstétrique'),
    ('5e000000-0000-4000-8000-000000000006', 'Ophtalmologie'),
    ('5e000000-0000-4000-8000-000000000007', 'Oto-rhino-laryngologie (ORL)'),
    ('5e000000-0000-4000-8000-000000000008', 'Neurologie'),
    ('5e000000-0000-4000-8000-000000000009', 'Pneumologie'),
    ('5e000000-0000-4000-8000-00000000000a', 'Gastro-entérologie'),
    ('5e000000-0000-4000-8000-00000000000b', 'Endocrinologie'),
    ('5e000000-0000-4000-8000-00000000000c', 'Rhumatologie'),
    ('5e000000-0000-4000-8000-00000000000d', 'Psychiatrie'),
    ('5e000000-0000-4000-8000-00000000000e', 'Urologie'),
    ('5e000000-0000-4000-8000-00000000000f', 'Néphrologie')
ON CONFLICT DO NOTHING;
