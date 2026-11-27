-- Scheduling follows MedLink's durable account status operations, without copying passwords or roles.
ALTER TABLE practitioners ADD COLUMN booking_enabled BOOLEAN NOT NULL DEFAULT TRUE;
