# Mapploy frontend

JavaScript, HTML and CSS frontend with an interactive MapLibre map, job reports, interest filters and local CV review.

## Run

Use Node.js 22 or newer:

```bash
npm ci
npm start
```

Open `http://127.0.0.1:4173`. Start the [Spring Boot backend](../backend/README.md) on port `8081` as well: the browser uses that API for jobs, analysis and CV review. The [root README](../README.md) includes the complete setup.

```bash
npm run check
```

This checks syntax in the Node server, browser application and pilot evaluation script.

## Earlier prototype API

`server.mjs` serves the UI and MapLibre files. It also retains the original Node-only job API, its own feed refresh and a JSON cache in `data/jobs.json`. The current UI calls the Spring backend; the two APIs do not share storage. The older prototype was called JobLens, which remains in one browser storage migration key and the evaluation environment variable for compatibility.

`npm run evaluate` is a historical engineering pilot against a fixed set of job IDs, not a repeatable test suite. It defaults to the Node API on port 4173; `JOBLENS_URL` overrides the target. It requires those vacancies and a local model, and may fail as feeds change. Generated pilot reports are excluded from Git.
