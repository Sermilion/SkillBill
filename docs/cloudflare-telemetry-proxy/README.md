# Cloudflare Worker telemetry proxy

This example lets Skill Bill clients send telemetry to a small Cloudflare Worker instead of the default hosted relay while keeping the client payload backend-agnostic. It now also shows one way to expose aggregate remote workflow stats through the same proxy boundary.

## What it does

1. Accepts `POST /` requests containing a JSON body with a `batch` array.
2. Performs lightweight validation on the incoming batch.
3. Adds the server-side `POSTHOG_API_KEY` for this example backend.
4. Forwards the batch to PostHog `/batch/`.
5. Exposes `GET /capabilities` so clients can discover whether the relay supports ingest, stats, and per-event deduplication.
6. Accepts `POST /stats` requests containing a workflow name plus `date_from` / `date_to`.
7. Optionally enforces a bearer token for `/stats`.
8. Queries PostHog through its Query API and returns aggregate workflow metrics.

The Worker forwards the privacy-scoped payload already produced by the
`skill-bill` CLI and MCP server. With the default `anonymous` level, review
telemetry is limited to:

- completed review run snapshots with aggregate finding counts, accepted/rejected finding metadata (finding id, severity, confidence, and outcome type only), and nested learning metadata

At `anonymous`, it excludes:

- repository identity, branch names, and file paths
- raw review text, finding descriptions, and rejection notes
- learning content (title, rule text, rationale)
- local-only learning bookkeeping events

At `full`, Skill Bill may include finding descriptions, file locations,
rejection notes, and learning content before the Worker forwards the batch.
Choose `full` only for deployments where that detail is acceptable.

## Deploy

1. Install Wrangler.
2. Copy `wrangler.toml.example` to a local `wrangler.toml`. That generated file is intentionally ignored and should stay machine-specific.
3. From this directory, set the PostHog project key used for event ingestion:

   ```bash
   wrangler secret put POSTHOG_API_KEY
   ```

4. If you want `/stats` support, also set:

   ```bash
   wrangler secret put POSTHOG_PERSONAL_API_KEY
   wrangler secret put POSTHOG_PROJECT_ID
   wrangler secret put PROXY_STATS_BEARER_TOKEN
   ```

   Set `PROXY_STATS_BEARER_TOKEN` on any public deployment. Without it, `/stats` answers anyone
   who knows the URL.

   Optional variables:

   - `POSTHOG_APP_HOST` defaults to `https://us.posthog.com` and is used for Query API calls
   - `POSTHOG_HOST` defaults to `https://us.i.posthog.com` and is used for event ingestion

5. Deploy:

   ```bash
   wrangler deploy
   ```

6. Confirm the deployed Worker runs this checkout's contract:

   ```bash
   npm run --silent check-deployed -- https://your-worker.your-subdomain.workers.dev
   ```

The example defaults `POSTHOG_HOST` to the US ingest endpoint and `POSTHOG_APP_HOST` to the US app/API endpoint. If you use EU Cloud or self-hosted PostHog, update those in your local `wrangler.toml` before deploying.

## Add the proxy as the telemetry destination

Set the proxy URL on the machine running Skill Bill:

```bash
export SKILL_BILL_TELEMETRY_PROXY_URL="https://your-worker.your-subdomain.workers.dev"
```

When this variable is set, Skill Bill sends telemetry to your Worker only.

Then keep using the normal telemetry commands:

```bash
sona telemetry status
sona telemetry sync
```

If you want to query org-wide workflow metrics through the same proxy, also set a stats token on the machine running Skill Bill:

```bash
export SKILL_BILL_TELEMETRY_PROXY_STATS_TOKEN="replace-with-your-worker-token"
```

Then query remote aggregate stats through the proxy contract:

```bash
sona telemetry capabilities
sona telemetry stats verify --since 30d
sona telemetry stats verify --date-from 2026-04-01 --date-to 2026-04-22
sona telemetry stats verify --since 30d --group-by day
sona telemetry stats verify --since 30d --group-by week
```

## Event deduplication and delivery acknowledgement

Every outbox row carries a durable, opaque identity minted in the same write that enqueues it. The client sends it as PostHog's `$insert_id` property. The identity is never derived from payload bytes, event name, or timestamp, so two real emissions with identical content stay two logical events, and it is never re-minted on rebatch, retry, restart, or reclaim.

The Worker forwards event properties verbatim: `isValidEvent` only inspects them, and `transformBatch` spreads them into both the pass-through branch and the `$exception` rewrite branch. Consequently:

- **Rollout order is producer-first.** A client may start sending `$insert_id` before any relay is redeployed; today's relay already forwards it. No coordinated deploy is required.
- **A fork that rewrites or drops properties must report `supports_event_deduplication: false`** from `GET /capabilities`. Every client path that reads capabilities — `sona telemetry capabilities`, `telemetry stats`, and the `telemetry_proxy_capabilities` MCP tool — then fails loudly instead of reporting a relay that cannot deduplicate as usable. The ingest drain does not fetch capabilities per batch, so this is a configuration-time check, not a per-send guard. Omitting the field means "supported", which keeps older relays working.

Deduplication retention is bounded by the receiver, not by this relay. PostHog's `$insert_id` deduplication is a finite ingestion-window mechanism keyed on the event name, `distinct_id`, timestamp, and `$insert_id` together. A retry that arrives after that window has passed is ingested as a separate event. This is best-effort duplicate suppression for retries that happen within normal delivery latency; it is **not** indefinite exactly-once ingestion, and no part of this design should be described as such. Verify the current window against PostHog's supported API documentation for your deployment rather than assuming a fixed value.

`forwardBatch` acknowledges a batch only after PostHog accepts it. Two paths in this Worker lose that acknowledgement without losing the data:

- the 10-second `AbortController` timeout, which can fire after the upstream already accepted the batch
- the `502` rewrite of an upstream error, which can follow a partially completed upstream write

The client treats both as an **unknown** outcome: the rows stay pending with their original identity and are retried, and the receiver deduplicates them. Treating either as a rejection would discard delivered events; treating either as success would drop undelivered ones.

## Health dashboard guidance

The Worker `/stats` route returns compact remote workflow summaries. Rich review-health and feature-task-health views remain backend-agnostic in the CLI/MCP local stats contract and are documented as PostHog/HogQL dashboard patterns in `../review-telemetry.md`.

Use the same filtering rules in PostHog dashboards:

- choose an explicit health window, commonly the last 60 days
- excluded rows: malformed records only. The runtime emits no test or synthetic source marker, so a dashboard cannot filter on one; if a project's events include test traffic, report that caveat rather than filtering on a property nothing sets
- data-quality debt: malformed session ids, unknown sources, duplicate terminal events, invalid/long-running durations, malformed child steps, and malformed review payloads
- review health sources: standalone `skillbill_review_finished`
- large-feature guidance: report `LARGE` completion, abandonment, and error separately, and recommend decomposition or earlier blocking when large-feature unhealthy rate is non-zero and at least the overall unhealthy rate

## Notes

- This example is intentionally minimal; it protects the PostHog project key, not the endpoint itself.
- If you expect public traffic, add Cloudflare rate limiting, bot protection, or other filtering on top of this Worker.
- The Skill Bill client uses three relay operations:
  - `POST /` with `{"batch": [...]}`
  - `GET /capabilities`
  - `POST /stats` with `{"workflow": "...", "date_from": "...", "date_to": "...", "group_by": "day|week"}` (`group_by` optional)
- If you use another analytics backend, adapt this Worker to translate those payloads before forwarding or querying.
- The `/stats` route in this example is intentionally PostHog-specific. The Skill Bill CLI and MCP server are not.
- `in_progress_runs` in the normalized `/stats` response is derived as `max(started_runs - finished_runs, 0)` for the requested date window.
- Retired event names (`skillbill_feature_task_prose_*`, `skillbill_feature_implement_*`, `skillbill_goal_prose_*`) pass through unchanged: the Worker applies no event-name filtering, so a retired name never fails a batch and is relayed upstream to the analytics backend like any other event. The retirement is enforced only by removing those names from the `/stats` queries, so no aggregate counts them. Already-installed clients on older Skill Bill versions must keep receiving normal success responses rather than errors, so the pass-through is deliberate.
- grouped `/stats` series are trend-oriented event-window buckets, not session-cohort analytics.
