ALTER TABLE reviews
    ADD COLUMN IF NOT EXISTS fix_round INTEGER NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_reviews_fix_round'
    ) THEN
        ALTER TABLE reviews ADD CONSTRAINT ck_reviews_fix_round
        CHECK (fix_round >= 0 AND fix_round <= 5);
    END IF;
END $$;
