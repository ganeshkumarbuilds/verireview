# docker/ — container assets (Phase 2+)

Empty in Phase 0 except for notes. Later phases add:

- `docker/backend.Dockerfile` (Phase 1/18)
- `docker/frontend.Dockerfile` (Phase 4/18)
- `docker/ai-service.Dockerfile` (Phase 7/18)
- `docker/sandbox/*` (Phase 10, hardened Phase 16; initial limits in ADR-007)

Sandbox limits (frozen): timeout 60s, memory 512 MB, CPU 1 core, output 5 MB, project 50 MB, 2000 files.
