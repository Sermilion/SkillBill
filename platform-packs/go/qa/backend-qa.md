# Go backend QA

Exercise the changed backend behavior against a disposable local environment built from the project's own test setup, and report what the acceptance criteria show. Follow the isolation, evidence and report rules in the QA briefing.

## Environment discovery

- Look for an existing environment before creating one: `compose.yaml`, `docker-compose*.yml`, `Makefile` or `Taskfile` targets that start dependencies, and `_test` env files such as `.env.test` or `config.test.yaml`.
- Find Testcontainers fixtures: `testcontainers-go` modules, `TestMain` setup, and shared helpers under `internal/testutil` or `testdata`.
- Find local isolated setups such as scripts that start dependencies, and `.env.example` templates.
- Reuse one existing setup as the only environment owner. Do not start a second stack beside it.

## Disposable database and migrations

- Use the engine the project already runs (for example a Postgres or MySQL container), started fresh for this run and bound to `localhost`.
- Apply the project's real migrations: `golang-migrate` (`migrate -path <dir> -database <url> up`) or `goose` (`goose up`), or the embedded migration runner the app calls at startup. Do not replace migrations with a hand-written schema.

## Synthetic data

- Seed through the project's fixture builders, test helpers or application APIs.
- Create dedicated QA accounts for the run (for example `<qa-user>`), with synthetic data only. Never reuse real user data.

## Start the app

- Use the project's usual start command, for example `go run ./cmd/<service>` or the Makefile run target.
- Point it at the disposable dependencies through environment variables or the test config file, such as `http://localhost:<port>` and a `localhost` database URL.
- Wait for a readiness signal, such as a health endpoint returning 200, a log line, or an open port. A readiness probe is setup, not an assertion.

## Exercise acceptance endpoints

- Call the changed endpoints with `net/http`, the project's API client, or a small temporary script written in the invocation directory.
- Assert the status code, the response content and the persisted or asynchronous side effect for each acceptance criterion.
- Read the side effect back through the database or a follow-up API read. A 2xx response alone is not a pass.
