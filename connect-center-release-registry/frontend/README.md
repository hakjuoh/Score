# connect-center-release-registry frontend

React dashboard for browsing exported releases and surfacing release availability
to operators.

## Commands

```bash
npm run dev
npm run build
npm run lint
```

## API expectations

During local development, `/api` is proxied to `http://localhost:5133`.

The dashboard expects:

- `GET /api/dashboard`
- `GET /api/releases`
- `GET /api/releases/availability`
