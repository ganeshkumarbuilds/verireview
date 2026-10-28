-- Auth lookups normalize email to lowercase; enforce and accelerate that key.
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lower ON users (lower(email));
