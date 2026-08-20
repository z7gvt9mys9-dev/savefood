ALTER TABLE public.volunteers
    ADD COLUMN kyc_generation text;

-- Bind pre-existing volunteer documents to a stable generation without
-- changing their moderation state or analysis result.
UPDATE public.volunteers
SET kyc_generation = 'legacy-' || id::text || '-'
    || md5(COALESCE(document, '') || '|' || created_at::text)
WHERE document IS NOT NULL;

ALTER TABLE public.volunteers
    ADD CONSTRAINT volunteers_kyc_document_generation_pair
    CHECK ((document IS NULL AND kyc_generation IS NULL)
        OR (document IS NOT NULL AND kyc_generation IS NOT NULL));
