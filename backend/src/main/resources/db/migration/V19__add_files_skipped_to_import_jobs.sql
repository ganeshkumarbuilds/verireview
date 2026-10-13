-- Add files_skipped column to import_jobs table
ALTER TABLE import_jobs
ADD COLUMN files_skipped BIGINT NOT NULL DEFAULT 0;