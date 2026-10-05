# Mapploy backend

Spring Boot API for Mapploy's continuously refreshed, evidence-grounded job search. It keeps the existing prototype API contract and uses only local or free components:

- Spring Boot 4.1.1 and Java 25
- Spring AI 2.0.1 with local Ollama (`gemma3:4b`)
- PostgreSQL 17 for the target architecture
- Flyway migrations
- H2 file storage as a zero-setup development fallback

## What works

- Syncs seven official Personio employer feeds at startup and once per day.
- Keeps first/last-seen timestamps and marks a missing job inactive only after three successful feed checks.
- Stores roles from all professional fields in Bremen, Hamburg, or remote and assigns an interest category.
- Creates a transparent rule-based analysis immediately.
- Runs a structured local-LLM analysis on demand and verifies every quoted evidence fragment against the original advertisement.
- Reviews PDF, DOC, DOCX or TXT CVs locally with a transparent checklist and optional selected-job evidence match.
- Does not persist uploaded CV files or extracted CV text.
- Masks recognised personal details before local CV AI review and sanitises narrative output and CV evidence before returning it.
- Exposes the same core endpoints as the Node prototype.

## Start locally without Docker

For optional local AI, run Ollama and install `gemma3:4b`. Rule-based reports and deterministic CV checks work without a model:

```powershell
ollama serve
ollama pull gemma3:4b
```

In a second terminal:

```powershell
# Set JAVA_HOME to your installed JDK 25 directory if necessary.
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=dev'
```

The API is available at `http://127.0.0.1:8081`. The development profile persists data in `backend/data/`.

## Start with PostgreSQL

With a Docker-compatible engine running:

```powershell
docker compose up -d postgres
.\mvnw.cmd spring-boot:run
```

PostgreSQL is published on host port `5433` to avoid common conflicts. Default credentials are suitable only for local development and can be overridden with `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`.

## API

- `GET /api/jobs` — all stored jobs and last sync state
- `POST /api/sync` — refresh all configured feeds now
- `POST /api/jobs/{id}/analyze` — re-analyze one job with the local LLM
- `POST /api/cv/analyze` — locally review a multipart CV upload; optionally pass `jobId` for an evidence-based comparison
- `GET /api/status` — Ollama/model and feed configuration
- `GET /actuator/health` — application health

The frontend at port `4173` is allowed through CORS and already calls the Spring API on port `8081`. Start it with `npm ci` and `npm start` inside `prototype/`, or use the [Windows launcher](../START-MAPPLOY.md). The backend binds to `127.0.0.1` by default; `SERVER_ADDRESS` overrides this for an explicitly configured deployment.

## Verify

### CV privacy boundary

The original extracted CV stays in the request's memory for the deterministic checklist and requirement matching. `CvPrivacyService` creates a separate masked version for Ollama: email addresses, phone numbers, profile links, labelled personal fields, common street/postal address formats, and the document header before a recognised section (up to 16 non-empty lines). Names are learned from labelled fields and likely header names, including Unicode names and common formatting variants. Known name parts are also masked when repeated elsewhere.

`LocalCvReviewService` accepts only this redacted CV type. It sanitises the selected-job context and complete prompt as well. `CvAnalysisService` filters `summary`, `strengths`, `improvements` and returned job-match evidence using the request's identity patterns plus generic contact/address patterns. The checklist score uses the original text, so masking does not remove contact points. A failed local model call still returns the deterministic report. CV text and the identity patterns are not logged, cached or stored by these services.

This is deterministic pattern-based minimisation, not guaranteed anonymisation: unusual layouts, unlabelled names outside the header, transliterations, and uncommon address formats can evade detection; conservative matches can also hide useful text. The original upload filename remains in the API response for the uploader and is never sent to Ollama. Keep processing local. The privacy tests use synthetic identities, inspect the actual model prompt, exercise deliberately leaking model responses, and cover the offline/error fallback.

```powershell
.\mvnw.cmd test
```
