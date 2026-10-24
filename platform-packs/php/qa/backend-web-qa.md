# PHP backend and web QA

Exercise the changed backend and server-rendered web behavior against a disposable local environment built from the project's own test setup, and report what the acceptance criteria show. Follow the isolation, evidence and report rules in the QA briefing.

## Backend

### Environment discovery

- Look for an existing environment before creating one: `compose.yaml`, `docker-compose*.yml`, Laravel Sail, `.env.testing`, `phpunit.xml*` environment entries, and the Symfony `test` environment (`.env.test`, `config/packages/test`).
- Find Testcontainers fixtures (`testcontainers-php`) and local isolated setups such as `composer` scripts or `Makefile` targets that start dependencies.
- Reuse one existing setup as the only environment owner. Do not start a second stack beside it.

### Disposable database and migrations

- Use the engine the project already runs (for example a MySQL or Postgres container), started fresh for this run and bound to `localhost`.
- Apply the project's real migrations: `php artisan migrate` or Doctrine `bin/console doctrine:migrations:migrate`. Do not replace migrations with a hand-written schema.

### Synthetic data

- Seed through the project's fixtures: Laravel factories and seeders, Doctrine fixtures, or application APIs.
- Create dedicated QA accounts for the run (for example `<qa-user>`), with synthetic data only. Never reuse real user data.

### Start the app

- Use the project's usual start command with the test environment, for example `php artisan serve --port=<port>` with `APP_ENV=testing`, or `symfony serve` with `APP_ENV=test`.
- Point it at the disposable dependencies through the test env file, such as a `localhost` database host.
- Wait for a readiness signal, such as a health route returning 200, a log line, or an open port. A readiness probe is setup, not an assertion.

### Exercise acceptance endpoints

- Call the changed endpoints with Guzzle, the framework HTTP test client, or a small temporary script written in the invocation directory.
- Assert the status code, the response content and the persisted or asynchronous side effect (queued job, dispatched message) for each acceptance criterion.
- Read the side effect back through the database or a follow-up API read. A 2xx response alone is not a pass.

## Web

### Browser driver discovery

- Look for an existing Playwright configuration (`playwright.config.*`) and its `webServer` block. Reuse its start command and base URL.
- Otherwise detect a usable CLI browser driver in the worker: `npx playwright --version` or `python -m playwright --version`.
- When no driver is found, name the missing capability in the report rather than claiming browser coverage.

### Authentication

- Authenticate synthetic QA users through a stored test storage-state file kept in the invocation directory, created by logging in once as `<qa-user>`.
- Never use an operator browser profile or real session cookies.

### Server-rendered journeys

- Exercise the changed Blade or Twig journeys: navigate, fill forms, submit, and assert visible content, redirects, validation errors and the persisted result.
- Save screenshots in the invocation directory. A page load alone is not a check.

### Backend reach versus mocks

- For each UI check, record whether it reached the disposable backend or was served by a mock (`page.route`, a stubbed fixture, a recorded response).
- Do not count a mock-served check as proof of backend behavior.
