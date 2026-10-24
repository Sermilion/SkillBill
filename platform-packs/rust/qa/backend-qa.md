# Rust backend QA

Exercise the changed backend behavior against a disposable local environment built from the project's own test setup, and report what the acceptance criteria show. Follow the isolation, evidence and report rules in the QA briefing.

## Environment discovery

- Look for an existing environment before creating one: `compose.yaml`, `docker-compose*.yml`, `justfile` or `Makefile` targets that start dependencies, and `.env.test` or `.env.example` files.
- Find Testcontainers fixtures: `testcontainers-rs` (`testcontainers` and `testcontainers-modules`) and shared helpers under `tests/common`.
- Find database test setups: `#[sqlx::test]` with `DATABASE_URL`, `diesel` test databases, and `config/test.*` files.
- Reuse one existing setup as the only environment owner. Do not start a second stack beside it.

## Disposable database and migrations

- Use the engine the project already runs (for example a Postgres or MySQL container), started fresh for this run and bound to `localhost`.
- Apply the project's real migrations: `sqlx migrate run` or `diesel migration run`, or the embedded migrator the app calls at startup. Do not replace migrations with a hand-written schema.

## Synthetic data

- Seed through the project's fixture builders, test helpers or application APIs.
- Create dedicated QA accounts for the run (for example `<qa-user>`), with synthetic data only. Never reuse real user data.

## Start the app

- Use the project's usual start command, for example `cargo run --bin <service>`.
- Point it at the disposable dependencies through environment variables or the test config, such as `http://localhost:<port>` and a `localhost` `DATABASE_URL`.
- Wait for a readiness signal, such as a health endpoint returning 200, a log line, or an open port. A readiness probe is setup, not an assertion.

## Exercise acceptance endpoints

- Call the changed endpoints with the project's HTTP client (`reqwest`) or a small temporary script written in the invocation directory.
- Assert the status code, the response content and the persisted or asynchronous side effect for each acceptance criterion.
- Read the side effect back through the database or a follow-up API read. A 2xx response alone is not a pass.
