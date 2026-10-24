# TypeScript backend and web QA

Exercise the changed Node service and browser frontend behavior against a disposable local environment built from the project's own test setup, and report what the acceptance criteria show. Follow the isolation, evidence and report rules in the QA briefing.

## Backend services

- Environment discovery: look for an existing environment before creating one: `compose.yaml`, `docker-compose*.yml`, `.env.test`, `package.json` scripts that start dependencies, and Jest or Vitest global setup files. Find `testcontainers` for Node fixtures. Reuse one existing setup as the only environment owner and do not start a second stack beside it.
- Disposable database: use the engine the project already runs (for example a Postgres or MySQL container), started fresh for this run and bound to `localhost`. Apply the project's real migrations: Prisma (`prisma migrate deploy`), TypeORM (`typeorm migration:run`), Knex (`knex migrate:latest`) or Drizzle (`drizzle-kit migrate`). Do not replace migrations with `db push` or a hand-written schema unless the project's test setup already does.
- Synthetic data: seed through the project's fixture builders, seed scripts or application APIs. Create dedicated QA accounts for the run (for example `<qa-user>`), with synthetic data only. Never reuse real user data.
- Start the app: use the project's usual start script (for example `npm run start`, `npm run dev` or `node dist/main.js`) with the test environment variables, pointed at the disposable dependencies on `localhost:<port>`. Wait for a readiness signal, such as a health endpoint returning 200, a log line, or an open port. A readiness probe is setup, not an assertion.
- Acceptance endpoints: call the changed endpoints with `fetch`, supertest, the project's API test client, or a small temporary script written in the invocation directory. Assert the status code, the response content and the persisted or asynchronous side effect (queued job, emitted event). Read the side effect back through the database or a follow-up API read. A 2xx response alone is not a pass.

## Frontend environment

- Look for `playwright.config.*` and its `webServer` block. Reuse its start command and base URL.
- Otherwise start the framework's dev or preview server (Vite `vite` or `vite preview`, Next.js `next dev` or `next start`) with QA-only environment variables, such as an API base URL pointing at the disposable backend on `localhost:<port>`.
- A static or UI-only frontend runs without Docker. Start containers only when the changed journeys need a real backend.

## Mocks

- Find the mock tools in use: MSW handlers (`handlers.ts`, `setupWorker`, `setupServer`) and Playwright `page.route` or `context.route` calls.
- Record for each assertion whether it hit the disposable backend or a mock. Do not count a mock-served check as proof of backend behavior.

## Authentication

- Authenticate synthetic QA users through Playwright `storageState` files kept in the invocation directory, created by logging in once as `<qa-user>`.
- Never use an operator browser profile or real session cookies.

## Browser journeys

- Run the existing Playwright specs for the changed journeys, or a temporary spec written in the invocation directory.
- Save screenshots and traces in the invocation directory. A page load alone is not a check.
- When no browser driver is usable (`npx playwright --version` fails), name the missing capability rather than claiming browser coverage.

## Layout and keyboard

- Check responsive layouts and keyboard interaction only when the changed criteria require them.
- For layouts, use the viewport sizes the criteria name. For keyboard, assert focus order and activation of the changed controls.
