-- Volunteer carrying capacity (§14 "максимальный суммарный вес маршрута").
--
-- A lot is claimed whole by one volunteer, so the only weight question that
-- matters is whether that volunteer can physically carry this lot. The platform
-- already knows the lot's weight (initial_quantity × unit_weight_kg); it had no
-- idea whether the person on the other side is on foot with a backpack or
-- driving a car.
--
-- NULL means "no declared limit" — every existing volunteer keeps working exactly
-- as before, and the check is skipped for them. A value is a self-declared
-- maximum in kilograms.
ALTER TABLE volunteers ADD COLUMN IF NOT EXISTS capacity_kg DOUBLE PRECISION;

COMMENT ON COLUMN volunteers.capacity_kg IS
    'Self-declared carrying capacity in kg; NULL = no limit declared.';
