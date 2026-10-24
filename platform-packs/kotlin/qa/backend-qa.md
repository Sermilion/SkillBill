# Kotlin backend QA

Exercise the changed backend behavior against a disposable local environment built from the project's own test setup, and report what the acceptance criteria show. Follow the isolation, evidence and report rules in the QA briefing.

## Environment discovery

- Look for an existing environment before creating one: `compose.yaml`, `docker-compose*.yml`, `src/test/resources/application-test.*`, and Spring `application-test` or `application-local` profiles. For Ktor, look for test `application.conf` or `application-test.yaml` and `testApplication` setup.
- Find Testcontainers fixtures: `@Testcontainers`, `@Container`, `@ServiceConnection`, `@DynamicPropertySource`, and shared base classes or `companion object` containers.
- Find local isolated setups such as Gradle tasks or scripts that start dependencies, and `.env.example` or `.env.test` templates.
- Reuse one existing setup as the only environment owner. Do not start a second stack beside it.

## Disposable database and migrations

- Use the engine the project already runs (for example a Postgres or MySQL container), started fresh for this run and bound to `localhost`.
- Apply the project's real migrations: Flyway (`flywayMigrate` or startup migration) or Liquibase (`update`). Do not replace migrations with a hand-written schema or `ddl-auto` shortcuts unless the project's test profile already does.

## Synthetic data

- Seed through the project's fixture builders, test factories or application APIs.
- Create dedicated QA accounts for the run (for example `<qa-user>`), with synthetic data only. Never reuse real user data.

## Start the app

- Use the project's usual start command with the test or local profile, for example `./gradlew bootTestRun` when the profile or `@ServiceConnection` containers live in the test source set, `./gradlew bootRun --args='--spring.profiles.active=local'`, `./gradlew run`, or the Ktor module's main class.
- Point it at the disposable dependencies through environment variables or profile properties, such as `http://localhost:<port>` and a `localhost` database URL.
- Wait for a readiness signal, such as `/actuator/health` returning `UP`, a log line, or an open port. A readiness probe is setup, not an assertion.

## Exercise acceptance endpoints

- Call the changed endpoints with the project's HTTP client (`WebTestClient`, Ktor `HttpClient`, `RestAssured`) or a small temporary script written in the invocation directory.
- Assert the status code, the response content and the persisted or asynchronous side effect for each acceptance criterion.
- Read the side effect back through the database or a follow-up API read. A 2xx response alone is not a pass.
