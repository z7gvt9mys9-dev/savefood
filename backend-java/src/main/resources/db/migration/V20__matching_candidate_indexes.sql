-- Restrict city candidate scans before applying top-N matching limits.
CREATE INDEX idx_matching_needy_city ON needy_profile (city, needy_id)
    WHERE preferences IS NOT NULL AND TRIM(preferences) <> '';
CREATE INDEX idx_matching_volunteer_city ON volunteers (city, id)
    WHERE availability IS NOT NULL AND TRIM(availability) NOT IN ('', '[]');
