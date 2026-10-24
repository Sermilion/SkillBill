# Python backend and web QA

Exercise the changed backend and server-rendered web behavior against a disposable local environment built from the project's own test setup, and report what the acceptance criteria show. Follow the isolation, evidence and report rules in the QA briefing.

## Backend

### Environment discovery

- Look for an existing environment before creating one: `compose.yaml`, `docker-compose*.yml`, `conftest.py` fixtures, `pytest.ini` or `pyproject.toml` pytest settings, `.env.test`, and Django `settings/test.py` or `DJANGO_SETTINGS_MODULE` overrides.
- Find Testcontainers fixtures (`testcontainers-python`, session-scoped container fixtures) and local isolated setups such as `Makefile` or `tox`/`nox` targets that start dependencies.
- Reuse one existing setup as the only environment owner. Do not start a second stack beside it.

### Disposable database and migrations

- Use the engine the project already runs (for example a Postgres or MySQL container), started fresh for this run and bound to `localhost`.
- Apply the project's real migrations: `alembic upgrade head` or Django `python manage.py migrate`. Do not replace migrations with `create_all` or a hand-written schema unless the project's test setup already does.

### Synthetic data

- Seed through the project's fixtures: pytest fixtures, `pytest-django` helpers, factory_boy factories, or application APIs.
- Create dedicated QA accounts for the run (for example `<qa-user>`), with synthetic data only. Never reuse real user data.

### Start the app

- Use the project's usual start command, for example `python manage.py runserver localhost:<port>`, `uvicorn <module>:app --port <port>`, or `flask run`.
- Point it at the disposable dependencies through environment variables or the test settings, such as a `localhost` database URL.
- Wait for a readiness signal, such as a health endpoint returning 200, a log line, or an open port. A readiness probe is setup, not an assertion.

### Exercise acceptance endpoints

- Call the changed endpoints with `httpx` or `requests`, the framework test client, or a small temporary script written in the invocation directory.
- Assert the status code, the response content and the persisted or asynchronous side effect for each acceptance criterion.
- Read the side effect back through the database or a follow-up API read. A 2xx response alone is not a pass.

## Web

### Browser driver discovery

- Look for an existing `playwright.config.*` and its `webServer` block, or `pytest-playwright` options (`--base-url`, `--browser`) in `pytest.ini`, `pyproject.toml` or `conftest.py`. Reuse the configured start command and base URL.
- Otherwise detect a usable CLI browser driver in the worker: `python -m playwright --version` or `npx playwright --version`.
- When no driver is found, name the missing capability in the report rather than claiming browser coverage.

### Authentication

- Authenticate synthetic QA users through a stored test storage-state file kept in the invocation directory, created by logging in once as `<qa-user>`.
- Never use an operator browser profile or real session cookies.

### Server-rendered journeys

- Exercise the changed Django or Jinja template journeys: navigate, fill forms, submit, and assert visible content, redirects and the persisted result.
- Save screenshots in the invocation directory. A page load alone is not a check.

### Backend reach versus mocks

- For each UI check, record whether it reached the disposable backend or was served by a mock (`page.route`, a stubbed fixture, a recorded response).
- Do not count a mock-served check as proof of backend behavior.
