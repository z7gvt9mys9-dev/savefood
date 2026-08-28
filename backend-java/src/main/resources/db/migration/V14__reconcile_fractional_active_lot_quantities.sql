-- A reservation always consumes one discrete lot unit.  Legacy real-valued
-- quantities are therefore reconciled down, never up: already reserved whole
-- units remain represented, while a fractional remainder cannot become stock.
UPDATE lots AS l
SET quantity = FLOOR(l.quantity),
    initial_quantity = FLOOR(COALESCE(l.initial_quantity, l.quantity)),
    status = CASE
        -- Keep a live reservation serviceable; its whole unit may be returned
        -- on cancellation, at which point the reconciled lot is claimable again.
        WHEN FLOOR(l.quantity) < 1 AND NOT EXISTS (
            SELECT 1 FROM tickets t
            WHERE t.lot_id = l.id AND t.status IN ('open', 'assigned')
        ) THEN 'removed'
        ELSE l.status
    END
WHERE l.status = 'active'
  AND l.quantity IS NOT NULL
  AND (l.quantity <> FLOOR(l.quantity) OR l.initial_quantity IS NULL
       OR l.initial_quantity <> FLOOR(l.initial_quantity));
