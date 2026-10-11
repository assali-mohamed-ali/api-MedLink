-- The primary key becomes a UUID (class diagram: User.id is a UUID).
ALTER TABLE users ADD COLUMN uuid_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE users DROP CONSTRAINT users_pkey;
ALTER TABLE users DROP COLUMN id;
ALTER TABLE users RENAME COLUMN uuid_id TO id;
ALTER TABLE users ADD PRIMARY KEY (id);
