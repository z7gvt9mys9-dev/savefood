CREATE INDEX ix_tickets_open_unassigned_expiry
    ON public.tickets (lot_id, expires_at, id)
    WHERE status = 'open'
      AND expires_at IS NOT NULL
      AND assigned_volunteer_id IS NULL
      AND assigned_volunteer IS NULL;
