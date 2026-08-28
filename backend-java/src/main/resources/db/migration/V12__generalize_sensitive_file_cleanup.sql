-- Reuse the durable recipient-KYC tombstone queue for every sensitive file
-- whose final live reference is removed by the Java backend.
ALTER TABLE public.needy_kyc_document_cleanup
    RENAME TO sensitive_file_cleanup;

ALTER TABLE public.sensitive_file_cleanup
    RENAME COLUMN document_ref TO file_ref;

ALTER TABLE public.sensitive_file_cleanup
    ADD COLUMN storage_type text;

UPDATE public.sensitive_file_cleanup
SET storage_type = 'needy_kyc';

ALTER TABLE public.sensitive_file_cleanup
    ALTER COLUMN storage_type SET NOT NULL,
    DROP CONSTRAINT needy_kyc_document_cleanup_document_ref_key,
    ADD CONSTRAINT sensitive_file_cleanup_storage_ref_key UNIQUE (storage_type, file_ref),
    ADD CONSTRAINT sensitive_file_cleanup_storage_type_check CHECK (
        storage_type IN ('needy_kyc', 'volunteer_kyc', 'delivery_photo', 'legacy_delivery_photo')
    );
