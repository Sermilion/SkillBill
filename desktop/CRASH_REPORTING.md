# Crash reporting

The desktop app uses Sentry's Java SDK. Reports go to the `sona` project in the
`sona-09` organization, hosted in Sentry's Germany region.

Reporting starts inside the app's runtime class loader before its services start.
The SDK captures uncaught JVM exceptions, including unhandled coroutine failures
that reach the JVM exception handler. The quit flow also reports failures that it
catches while closing chats. Sentry preserves the previous uncaught handler.
This integration does not instrument separate CLI, MCP or agent processes, native
JVM crashes, or launcher failures before the app's dependency graph exists.

## Collection controls

Each event checks the current effective telemetry level through the runtime's
config adapter. `off`, unreadable settings and an unavailable runtime suppress
the event. Changing telemetry to `off` takes effect without restarting the app.
The reporting code does not create an installation identity or change telemetry
preferences. Normal cancellation does not produce a report.

Set `SENTRY_DSN` to an empty string before starting Sona to disable SDK startup.
A nonempty `SENTRY_DSN` overrides the bundled project's public DSN. No Sentry
authentication token belongs in the app or its configuration.

Reports contain exception types and source locations, severity, SDK information,
and release and environment metadata. They omit exception messages, user details,
hostnames, prompts, breadcrumbs, request data, extra fields, arbitrary tags, thread
dumps, absolute source paths and source snippets. Both `anonymous` and `full`
telemetry use this same restricted error payload. Automatic sessions, client
reports, tracing and profiling stay disabled.
The payload replaces the user's IP address with the unspecified address
`0.0.0.0` so Sentry cannot infer a location from it. No user identity is collected,
so Sentry's impacted-user counts do not measure distinct people.

Release metadata comes from `build-identity.properties`. Clean release builds use
the `production` environment. Source and dirty builds use `development`.
`SENTRY_ENVIRONMENT` overrides the environment. Missing or invalid identity leaves
release metadata absent and produces a local warning. SDK initialization failures
also produce a local warning and do not prevent Sona from starting.

## Delivery check

Run this explicit task to send one synthetic exception. It uses no operator
database, does not launch the desktop UI, and labels the event `validation`.
Its synthetic policy allows this one report independently of the operator's
telemetry preference. The task never runs as part of `check`.

```sh
cd desktop
SENTRY_DSN='https://d89b832a03f4cd4773f0d44537e0dc4f@o4512428581191680.ingest.de.sentry.io/4512428598624336' ./gradlew :app:sentrySmoke
```

The task prints the event ID after flushing the SDK. Confirm that ID in Sentry
to prove receipt. A local event ID alone does not prove delivery.

Add `-PsentryCrash=true` to exercise the production initializer and uncaught JVM
handler instead of explicitly capturing an exception. Set `SENTRY_ENVIRONMENT=validation`
for this check. It throws `SonaSentryUncaughtCrashCheck` on the main thread without
catching it or calling `captureException`. The process must exit with status 1 and
Gradle reports a failed task. Confirm a fatal, unhandled event in Sentry before
counting the check as passed. This mode still uses an isolated synthetic telemetry
policy and does not open the desktop app or the operator's database.

The two crash reporting tests use an in-memory transport. They cover uncaught
handler delegation and payload scrubbing, then changing the policy to `off`,
unreadable policy and cancellation. They send no network requests.
