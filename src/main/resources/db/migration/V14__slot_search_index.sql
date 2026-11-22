-- MLink-99 (25.3): the slot search of a patient (SlotService.searchFree) asks for the free slots of a period,
-- earliest first, one page at a time. This index holds the free slots only, in that order: the search reads the
-- first rows of the period and stops at the page, instead of reading the whole table and sorting it.
-- The practitioner filter already has an index: slots_practitioner_starts_at_unique (practitioner_id, starts_at).
CREATE INDEX slots_free_by_start ON slots (starts_at, id) WHERE status = 'FREE';
