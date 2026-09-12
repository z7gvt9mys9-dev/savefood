-- Auto-KYC is advisory: profiles that were approved solely by the old automatic
-- path must return to the moderator queue. Manually approved profiles are untouched.
UPDATE volunteers
SET status = 'pending',
    kyc_notes = NULLIF(TRIM(REPLACE(kyc_notes, '[авто-одобрено ИИ]', '')), '')
WHERE status = 'approved'
  AND document IS NOT NULL
  AND kyc_notes LIKE '[авто-одобрено ИИ]%';
