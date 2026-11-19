-- Reference specialties (MLink-174), in every environment, like the clinics of V15: a practitioner needs one
-- (practitioners.specialty_id NOT NULL) and no screen or endpoint creates a specialty, so without them an admin
-- could not create a practitioner in staging or production. Same ids as the dev seed (db/dev-seed), whose insert
-- skips them with ON CONFLICT, so dev keeps working.

INSERT INTO specialties (id, name) VALUES
    ('5e000000-0000-4000-8000-000000000001', 'Médecine générale'),
    ('5e000000-0000-4000-8000-000000000002', 'Cardiologie'),
    ('5e000000-0000-4000-8000-000000000003', 'Pédiatrie')
ON CONFLICT DO NOTHING;
