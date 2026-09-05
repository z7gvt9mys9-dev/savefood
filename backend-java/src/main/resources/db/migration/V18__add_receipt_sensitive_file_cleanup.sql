ALTER TABLE public.sensitive_file_cleanup
    DROP CONSTRAINT sensitive_file_cleanup_storage_type_check,
    ADD CONSTRAINT sensitive_file_cleanup_storage_type_check CHECK (
        storage_type IN ('needy_kyc', 'volunteer_kyc', 'delivery_photo',
                         'legacy_delivery_photo', 'receipt')
    );
