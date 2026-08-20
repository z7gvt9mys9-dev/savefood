-- Route history needs delivery outcomes, not recipient delivery PII. Redact all
-- ticket points on terminal routes and already-terminal points on active routes.
-- Malformed legacy JSON is discarded because it cannot safely support navigation
-- or statistics and must not block removal of potentially sensitive values.
DO $$
DECLARE
    route_row RECORD;
    redacted_points text;
BEGIN
    FOR route_row IN
        SELECT id, volunteer_id, status, points
        FROM volunteer_routes
        WHERE points IS NOT NULL AND btrim(points) <> ''
    LOOP
        BEGIN
            SELECT COALESCE(jsonb_agg(
                CASE
                    WHEN point ->> 'kind' = 'ticket'
                         AND (route_row.status <> 'in_progress'
                              OR lower(COALESCE(point ->> 'done', 'false')) = 'true'
                              OR lower(COALESCE(point ->> 'cancelled', 'false')) = 'true'
                              OR lower(COALESCE(point ->> 'released', 'false')) = 'true'
                              OR lower(COALESCE(point ->> 'failed', 'false')) = 'true'
                              OR NOT EXISTS (
                                  SELECT 1
                                  FROM tickets
                                  WHERE tickets.id::text = (point ->> 'ticket_id')
                                    AND tickets.status = 'assigned'
                                    AND tickets.assigned_volunteer_id = route_row.volunteer_id
                              ))
                    THEN (
                        SELECT COALESCE(jsonb_object_agg(entry.key, entry.value), '{}'::jsonb)
                        FROM jsonb_each(point) AS entry
                        WHERE entry.key IN (
                            'kind', 'ticket_id', 'lot_id', 'shop_id', 'sequence', 'position',
                            'done', 'cancelled', 'released', 'failed', 'status', 'outcome',
                            'attempt_count', 'quantity', 'weight_kg', 'completed_at', 'fulfilled_at',
                            'cancelled_at', 'failed_at', 'terminal_at'
                        )
                    )
                    ELSE point
                END ORDER BY ordinal_position), '[]'::jsonb)::text
            INTO redacted_points
            FROM jsonb_array_elements(route_row.points::jsonb)
                 WITH ORDINALITY AS route_points(point, ordinal_position);

            UPDATE volunteer_routes SET points = redacted_points WHERE id = route_row.id;
        EXCEPTION WHEN OTHERS THEN
            UPDATE volunteer_routes SET points = '[]' WHERE id = route_row.id;
        END;
    END LOOP;
END $$;
