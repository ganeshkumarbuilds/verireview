# frontend/ — React dashboard (light theme)

Vite + React 18 + TypeScript (strict) + Tailwind CSS v4 + React Router v6.
Clean light UI: public landing homepage, workspace shell, wired auth +
projects pages (backend at `http://localhost:8080`).

Rule: the frontend calls the Spring Boot backend only (`/api/v1`) —
never the AI service or sandbox directly.

## Prerequisites

- Node.js 18+ (`node --version`)
- Backend optional (this shell runs standalone)

## Run

```powershell
cd frontend
npm install
npm run dev     # http://localhost:5173
```

## Build & test

```powershell
cd frontend
npm run build   # typecheck (tsc --noEmit) + static bundle in dist/
npm test        # Vitest + React Testing Library (jsdom)
```

## Layout

- `src/components/Navbar.tsx` — brand, tagline, sidebar toggle
- `src/components/Sidebar.tsx` — Workspace + Account sections (collapses below `md`)
- `src/components/Layout.tsx` — navbar + sidebar + `<Outlet/>` content area
- `src/components/ui.tsx` — `Card` + `Badge` design-system seed (severity/source tones)

## Routes

| Path            | Page                                              |
|-----------------|---------------------------------------------------|
| `/`             | Homepage (public landing, Login / Get started)    |
| `/dashboard`    | Dashboard                                         |
| `/projects`     | Projects (auth required)                          |
| `/projects/:id` | Project details (auth required)                   |
| `/review`       | Review                                            |
| `/history`      | History                                           |
| `/login`        | Login (wired to backend)                          |
| `/register`     | Register (wired to backend)                       |

## Phase 5: projects + auth wiring

- `src/auth/AuthContext.tsx` — in-memory session (token/user), login/register/
  logout, `RequireAuth` guard. No persisted storage, no silent refresh yet.
- `src/api/auth.ts`, `src/api/projects.ts` — login/register/me, project
  CRUD, ZIP upload (multipart), file list, capped file content.
- Pages: `ProjectsPage` (list, shell creation, ZIP upload), `ProjectDetailPage`
  (`/projects/:id`, metadata + file inventory + capped viewer), wired
  `LoginPage`/`RegisterPage`.
- Backend must allow the dev origin (CORS `http://localhost:5173`) and run on
  `http://localhost:8080` (see `ApiClient` default base URL).

## API structure

- `src/api/types.ts` — backend DTO mirrors (`UserResponse`, `TokenResponse`,
  projects, files, pages)
- `src/api/client.ts` — `ApiClient` (base URL, generic `request`) +
  `parseBody` + `ApiError` + `bearer` helper. No token storage (AuthContext).
