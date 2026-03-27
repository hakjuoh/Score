# Architecture Notes

## Goal

`connect-center-release-registry` is the operational front door for release
exports produced by `score-http`.

It needs to support:

- Dashboard users who want to see what exports exist and whether they are usable
- Other server instances that only need a lightweight answer to "is there a newer release available?"

## Planned shape

### Backend

- ASP.NET API
- Local SQLite snapshot generated from SCORE's MariaDB tables
- Library and release endpoints under `/api/libraries` and `/api/releases`
- Shared identity context via mirrored `app_user` records

### Frontend

- React + Vite
- ShadCN-style component structure
- Catalog-first library grid
- Library detail page showing releases and `release_dep` relationships

## Data flow

The registry now treats MariaDB as the source of truth and SQLite as the local
serving store.

Startup sequence:

1. Read `library`, `release`, `release_dep`, `namespace`, and `app_user` from MariaDB.
2. Exclude any release where `release_num = 'Working'`.
3. Rebuild the local SQLite snapshot.
4. Serve the dashboard and polling APIs from SQLite.

## API direction

The initial read-oriented API surface should stay small:

- `GET /api/health`
- `GET /api/dashboard`
- `GET /api/libraries`
- `GET /api/libraries/{libraryId}`
- `GET /api/libraries/{libraryId}/releases`
- `GET /api/releases`
- `GET /api/releases/availability?knownReleaseId=...`

That last endpoint is the one other server instances can call to determine whether
they should pull a newer exported release.
