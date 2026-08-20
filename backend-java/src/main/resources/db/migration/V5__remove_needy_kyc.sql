-- Recipient eligibility verification has been removed. Queue every legacy
-- recipient document before dropping its database reference so filesystem
-- failures remain durable and retryable. Volunteer KYC columns are untouched.
CREATE TABLE public.needy_kyc_document_cleanup (
    id bigserial PRIMARY KEY,
    needy_id integer,
    document_ref text NOT NULL UNIQUE,
    attempts integer NOT NULL DEFAULT 0,
    last_attempt_at timestamp with time zone,
    next_attempt_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error text,
    completed_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO public.needy_kyc_document_cleanup (needy_id, document_ref)
SELECT MIN(needy_id), document_ref
FROM (
    SELECT id AS needy_id, document AS document_ref
    FROM public.needy
    WHERE document IS NOT NULL AND BTRIM(document) <> ''
    UNION ALL
    SELECT needy_id, document AS document_ref
    FROM public.needy_profile
    WHERE document IS NOT NULL AND BTRIM(document) <> ''
) legacy_documents
GROUP BY document_ref;

-- KYC was the only meaning of these three states. Preserve unrelated states,
-- notably deleted, while making every legacy applicant immediately usable.
UPDATE public.needy
SET status = 'active'
WHERE status IN ('pending', 'approved', 'rejected');

ALTER TABLE public.needy ALTER COLUMN status SET DEFAULT 'active';

DELETE FROM public.notifications
WHERE needy_id IS NOT NULL
  AND type IN ('moderation_approved', 'moderation_rejected', 'kyc_doc_purged');

DELETE FROM public.audit_log
WHERE target_type = 'needy'
  AND (action LIKE 'kyc_%' OR action = 'needy_moderation');

ALTER TABLE public.needy
    DROP COLUMN document,
    DROP COLUMN kyc_score,
    DROP COLUMN kyc_verdict,
    DROP COLUMN kyc_notes,
    DROP COLUMN kyc_checked_at;

ALTER TABLE public.needy_profile DROP COLUMN document;
