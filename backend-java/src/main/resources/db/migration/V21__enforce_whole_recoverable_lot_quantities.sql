-- V14 deliberately reconciled only lots that were active at that time.  Taken
-- lots are also recoverable, so normalize both states before they can return to
-- the shelf.  Ticket quantities are discrete units; count only their whole
-- committed obligations and never increase available inventory.
WITH obligations AS (
    SELECT l.id,
           COALESCE(SUM(
               CASE WHEN t.status IN ('open', 'assigned', 'fulfilled')
                    THEN GREATEST(FLOOR(COALESCE(t.quantity, 0)), 0)
                    ELSE 0
               END
           ), 0) AS committed_whole
    FROM lots l
    LEFT JOIN tickets t ON t.lot_id = l.id
    WHERE l.status IN ('active', 'taken')
    GROUP BY l.id
), normalized AS (
    SELECT l.id,
           GREATEST(FLOOR(COALESCE(l.quantity, 0)), 0) AS available_whole,
           GREATEST(
               CASE WHEN l.initial_quantity IS NULL
                    THEN GREATEST(FLOOR(COALESCE(l.quantity, 0)), 0)
                         + o.committed_whole
                    ELSE GREATEST(FLOOR(l.initial_quantity), 0)
               END,
               o.committed_whole
           ) AS initial_whole,
           o.committed_whole,
           EXISTS (
               SELECT 1 FROM tickets serviceable
               WHERE serviceable.lot_id = l.id
                 AND serviceable.status IN ('open', 'assigned')
           ) AS has_serviceable_ticket
    FROM lots l
    JOIN obligations o ON o.id = l.id
    WHERE l.status IN ('active', 'taken')
), reconciled AS (
    SELECT id,
           LEAST(available_whole,
                 GREATEST(initial_whole - committed_whole, 0)) AS available_whole,
           initial_whole,
           has_serviceable_ticket
    FROM normalized
)
UPDATE lots l
SET quantity = r.available_whole,
    initial_quantity = r.initial_whole,
    status = CASE
        WHEN r.available_whole < 1 AND NOT r.has_serviceable_ticket THEN 'removed'
        ELSE l.status
    END
FROM reconciled r
WHERE l.id = r.id;

-- NOT VALID preserves terminal historical rows that can never be recovered,
-- while PostgreSQL still enforces each CHECK for every later INSERT or UPDATE.
ALTER TABLE lots
    ADD CONSTRAINT ck_lots_quantity_whole_nonnegative
    CHECK (quantity IS NULL OR (quantity >= 0 AND quantity = FLOOR(quantity)))
    NOT VALID;

ALTER TABLE lots
    ADD CONSTRAINT ck_lots_initial_quantity_whole_nonnegative
    CHECK (initial_quantity IS NULL OR
           (initial_quantity >= 0 AND initial_quantity = FLOOR(initial_quantity)))
    NOT VALID;

ALTER TABLE lots
    ADD CONSTRAINT ck_lots_recoverable_quantity_consistent
    CHECK (status NOT IN ('active', 'taken') OR
           (quantity IS NOT NULL AND initial_quantity IS NOT NULL
            AND quantity >= 0 AND initial_quantity >= 0
            AND quantity = FLOOR(quantity)
            AND initial_quantity = FLOOR(initial_quantity)
            AND quantity <= initial_quantity))
    NOT VALID;

ALTER TABLE lots VALIDATE CONSTRAINT ck_lots_recoverable_quantity_consistent;
