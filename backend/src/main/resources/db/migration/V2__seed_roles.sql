-- V2: seed the only static reference data (Phase 2).
-- Roles only — no demo users (DATABASE_DESIGN §7).

INSERT INTO roles (name) VALUES ('USER'), ('ADMIN')
ON CONFLICT (name) DO NOTHING;
