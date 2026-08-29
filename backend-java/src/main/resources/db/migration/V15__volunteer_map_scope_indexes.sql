-- City-scoped volunteer map: find shops in the requested area, then aggregate
-- open delivery tickets by lot without a per-lot correlated lookup.
CREATE INDEX idx_shops_city_id ON public.shops USING btree (city, id);
CREATE INDEX idx_tickets_lot_status ON public.tickets USING btree (lot_id, status);
