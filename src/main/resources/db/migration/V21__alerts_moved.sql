-- MLink-37: a move is told apart from a cancellation. The practitioner of the old slot gets APPOINTMENT_MOVED ("reporté",
-- with the new date) instead of APPOINTMENT_CANCELLED. The rule "one alert per appointment and type" stays.
ALTER TABLE alerts DROP CONSTRAINT alerts_type_check;
ALTER TABLE alerts ADD CONSTRAINT alerts_type_check CHECK (type IN ('APPOINTMENT_CANCELLED', 'APPOINTMENT_MOVED'));
