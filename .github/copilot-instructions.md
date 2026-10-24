# VeriReview project rules (always follow)

Stack: Spring Boot (Java 21, Hibernate, Flyway) backend on Render, React + Vite + TypeScript frontend on Vercel, Postgres on Neon, Python ai-service.

HARD RULES
- Never edit existing Flyway migrations (V1-V21). Schema changes only via new migrations (V22+), idempotent.
- Never run SQL, tests or migrations against the Neon production DB (host ep-sparkling-heart-b7rf27ot). Use Testcontainers or local docker-compose Postgres only. Never read or print .env secrets.
- Never run git commit or git push. Show the diff stat and stop.
- Never log or store users' API keys, DB URLs or passwords in plain text.
- Keep the existing error envelope {data, error:{code,message,details}, traceId} and add a top-level "status". The UI shows plain-language messages, never stack traces.
- Do not enable spring.main.allow-circular-references or global lazy-initialization. Keep ddl-auto=validate.
- Verification must be honest: if the Docker sandbox is disabled, builds/tests are "Not verified", never success. Never show a green state for skipped checks.
- Run tests and paste the real output. Never call failures "known issues"; fix them or say exactly why they cannot be fixed.
- Work in small phases. After each phase run the relevant tests/build, report PASS/FAIL, and STOP for my approval.
- No empty onClick handlers, no href="#", no TODO placeholders, no dead buttons.

UI RULES
- Simple, professional, consistent: reuse the existing Dashboard design (tokens, cards, buttons). Responsive at 375/768/1280px, keyboard focus rings, AA contrast, loading/empty/error states with Retry, null-safe rendering.