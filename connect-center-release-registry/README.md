# connect-center-release-registry

Registry workspace for exported release artifacts produced by
`score-http` via `exportRelease(...)`.

This project serves two audiences:

- Human operators using a dashboard to review exported releases
- Other server instances polling a small API surface to detect newer releases

## Workspace layout

- `backend/`: ASP.NET API and tests
- `frontend/`: React dashboard with ShadCN-style UI primitives
- `docs/`: architecture notes for export registry and shared authentication

## Initial scope

- Mirror release metadata from SCORE's MariaDB into a local SQLite snapshot
- Show library-first browsing and per-library release dependencies in the dashboard
- Expose release availability endpoints for inter-server polling
- Reuse mirrored `app_user` data from SQLite for shared identity context

## Local development

Scripts:

```bash
./scripts/run-backend.sh
./scripts/run-frontend.sh
./scripts/run-dev.sh
```

Manual backend:

```bash
cd backend
DOTNET_CLI_HOME=/tmp/codex-dotnet-home DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1 DOTNET_CLI_TELEMETRY_OPTOUT=1 NUGET_PACKAGES=/tmp/codex-nuget dotnet run --project src/ConnectCenter.ReleaseRegistry.Api
```

Manual frontend:

```bash
cd frontend
npm install
npm run dev
```

The Vite dev server proxies `/api` requests to `http://localhost:5133`.

## Runtime behavior

On backend startup, the API now:

- Connects to MariaDB at `127.0.0.1:3306` using the configured `oagi` credentials
- Copies `library`, `release`, `release_dep`, `namespace`, and `app_user` into a local SQLite file
- Excludes the special `Working` release from the mirrored catalog
- Serves dashboard and polling APIs from the SQLite snapshot

The default SQLite path is:

- `backend/src/ConnectCenter.ReleaseRegistry.Api/data/release-registry.db`
