DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_reviews_status'
    ) THEN
        ALTER TABLE reviews DROP CONSTRAINT ck_reviews_status;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_reviews_status'
    ) THEN
        ALTER TABLE reviews ADD CONSTRAINT ck_reviews_status
        CHECK (status IN ('QUEUED', 'RUNNING', 'NOT_ANALYZED', 'COMPLETED', 'FAILED'));
    END IF;
END $$;
