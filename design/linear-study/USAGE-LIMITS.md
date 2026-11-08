# Usage limits implementation draft

Drafted 2026-11-07. This is a technical proposal for the existing sidebar prototype, not an accepted feature spec or a started run. See [README](README.md#usage-limits-card) for the design and [HANDOFF](HANDOFF.md) for the app boundaries.

## Outcome and scope

Show current account allowances for the provider used by the open run or New task chat. Support Codex, Claude and Cursor. Leave Junie out. Keep the card in the existing sidebar and the details in its popup.

The card reports account usage now, including usage from other applications when the source exposes it. Opening a completed run does not turn it into a historical usage report. It does not pause runs, switch providers, buy credits, redeem resets, or change provider authentication.

Reliability takes priority over showing numeric meters for every provider. The first version uses documented interfaces whose behavior we can verify on supported CLI versions. Exclude undocumented endpoint polling, browser scraping and terminal-screen parsing. If a supported source cannot report an allowance, show an explicit unavailable state and a link to the provider's usage dashboard.

The operator accepts explicit unavailable states in v1 where no reliable documented usage source exists. Codex, Claude and Cursor remain in scope. Numeric readings require a verified source; numeric coverage for all three providers is not a release requirement.

Documentation alone does not establish reliability. Each enabled collector needs a verified account identity, validated payloads, bounded reads and tested failure behavior. A source that fails those checks remains unavailable. Reconsider additional sources separately if their support and compatibility become clear.

## What the providers expose

These findings describe documented capabilities, not successful authenticated calls on this machine. Research used documentation, local CLI help and version output, and Codex's locally generated protocol schema. No account credentials or live allowance responses were read.

| Provider | Candidate source | Capability and limit |
| --- | --- | --- |
| Codex | Local `codex app-server` over stdio, `account/rateLimits/read`, plus `account/rateLimits/updated` notifications | Account limits with optional buckets, durations and reset times. Use the selected local profile. Support depends on the account's authentication mode and installed CLI. |
| Claude | Existing session output and documented status-line JSON | The SDK documents `rate_limit_event`, an optional utilization and reset signal. Status-line JSON exposes named allowance windows and some gateway budgets. A complete, idle, machine-readable subscription snapshot through our headless transport still needs a feasibility check. |
| Cursor personal | Provider usage dashboard; investigate CLI capabilities during the feasibility check | No documented personal-account allowance snapshot API was found. CLI authentication status is not a quota response. Keep an explicit unsupported state unless a usable source is established. |
| Cursor team | Optional team Admin API configuration | Current billing-cycle spend is available with an admin API key. Spending is not necessarily the account's included allowance. Do not require an admin key for personal-account support. |

Codex returns `windowDurationMins` and optional `resetsAt` with its quota windows. It also has a multi-bucket response. Use those fields rather than assuming that primary means five hours or secondary means a week. Its token-activity endpoint measures a different quantity. [Codex app-server reference](https://developers.openai.com/codex/app-server)

Claude's status-line fields can be absent, and they appear only after an API response for eligible accounts. Their availability in an interactive terminal does not prove that our headless launcher can collect them. The SDK's rate-limit event is a partial signal, not a complete list of allowances. Validate supported `/usage` behavior and output on the installed CLI before choosing an idle collector. Never send a model prompt to obtain a meter or overwrite the user's status-line configuration. [Status-line reference](https://code.claude.com/docs/en/statusline), [SDK event reference](https://code.claude.com/docs/en/agent-sdk/typescript#sdkratelimitevent), [programmatic command availability](https://code.claude.com/docs/en/headless)

Cursor's allowance structure varies by plan. Its Admin API distinguishes on-demand spend from overall spend and exposes budget fields with different meanings. Match numerator and denominator before calculating a percentage. Aggregated usage endpoints document hourly updates and polling guidance; they cannot supply a live allowance meter. [Usage and limits](https://cursor.com/help/models-and-usage/usage-limits), [team Admin API](https://cursor.com/docs/account/teams/admin-api)

## Data shape

Use a list of typed meters. Do not introduce shared `fiveHour` and `weekly` fields.

| Type | Proposed contents |
| --- | --- |
| Usage subject | Provider, resolved profile reference, account or organization identity when known, and authentication mode. |
| Snapshot | Subject, collection time, optional source observation time, source and capability, meter list, freshness, and refresh state. |
| Meter | Stable provider bucket ID, label, model or pool scope, value, optional period, optional next reset, and observation time. |
| Value | Percentage used, amount used against a matching limit, credit balance, explicit unlimited allowance, or status without a numeric value. |
| Period | Provider-reported rolling duration, billing cycle boundaries, or unknown period. |
| Refresh result | Complete snapshot, partial observation, unsupported, sign-in required, temporarily failed, or provider throttled with a retry time. |

Money uses decimal amounts and a currency. Request, token, credit and money quantities keep their units. An absent limit does not mean unlimited. A zero denominator never produces a percentage. A credit balance without a consumption limit gets a balance row rather than a bar.

Keep explicit blocked or allowed signals separate from the percentages. A reset timestamp passing, or a percentage dropping, does not by itself confirm that the provider will allow another request.

Each adapter declares whether its payload replaces a complete snapshot or updates named fields. Merge partial events only within the same subject and bucket. An event without a bucket identity can update provider status but cannot overwrite an arbitrary bar. An authoritative response that omits a window follows that source's documented removal semantics.

Keep the last good observation and its age when refresh fails. Track request generations so a slow old response cannot overwrite a newer account, profile, or stream observation. Collection time is not proof of source freshness, especially for aggregated APIs.

## Context and account selection

- An open run follows its current executing agent. Resolve `Detail.activeAgent` before the recorded work-item agent and launch defaults. A goal can change providers between phases.
- New task follows the selected chat's agent and resolved profile. Switching chats changes the subject immediately.
- Work and History remember the manual provider selection. Keyboard focus on a board row does not silently change it.
- Junie and an unknown run provider hide the card. A supported provider with an unavailable data source shows a reason instead of disappearing.
- A known provider does not establish the run's account. Existing run projections do not reliably identify every historical account or profile. If that identity is missing, label the reading as the current local account. Do not claim it belongs to the run.

Use the same profile resolution as execution, including `CODEX_HOME`, Claude's selected configuration home and `CURSOR_CONFIG_DIR` where applicable. A profile path alone is not a permanent account identity. Revalidate identity after sign-in or account changes and isolate cached results across accounts and organizations. If identity cannot be established, do not reuse an account-bound cached reading.

The collapsed card shows at most two relevant meters. Prefer the selected model's pool and the most restrictive applicable allowance with a known denominator. Label each period. The popup shows every reported meter, account scope, source freshness and the reason for missing data. Dollar budgets and credit balances keep their own labels.

## Refresh policy

These intervals are starting product choices, not provider guarantees. The adapter's minimum interval and `Retry-After` always take precedence.

| Source | Proposed behavior |
| --- | --- |
| Codex account read | Fetch on selection if absent or older than 60 seconds. While the card is visible, poll every 60 seconds during active work and every five minutes otherwise. Apply notifications immediately. Polling still covers account use in other clients. |
| Claude session signals | Apply observations as they arrive. Do not poll a model session. Add timed refresh only after an independent, non-model snapshot source passes the feasibility check. |
| Cursor team spend | Refresh on selection when older than five minutes, then every five minutes while visible, subject to documented limits. If using hourly aggregated activity, fetch it at most hourly and label its age. |
| Unsupported personal source | No repeated request loop. Explain the limitation and offer the provider's usage dashboard. |

One application-scoped coordinator owns the cache and scheduled work. All windows observing a subject share one in-flight read and one schedule. Observe only subjects visible in a card or its popup. Release old subscriptions when context changes. Existing session observations can still update the cache without creating new network work.

Manual Refresh requests a new supported snapshot, coalesces with an in-flight request and has a ten-second cooldown. Disable it with an explanation for event-only or unsupported sources. Show the previous reading while checking. A successful read with no usable meters is an explicit empty result, not a fabricated green state.

Pause scheduled requests when no relevant window is visible. On resume or focus, refresh only if due. Drive these decisions from observable window state, not Compose recomposition. No new background daemon is needed.

When a reset time passes, mark the old period expired and request one new snapshot if the source supports it. Do not animate the allowance back to 100 percent. Keep the existing retry floor so an expired timestamp cannot create a tight loop. Display countdowns locally without network calls.

Use bounded reads and process startup. Start with a ten-second read deadline and a fifteen-second startup deadline, then validate against real provider behavior. Transient failures back off from 30 seconds to a fifteen-minute cap with jitter. Honor provider retry times. Authentication failures wait for an account or credential change rather than retrying every minute. A manual retry can recheck sign-in without bypassing provider throttling.

A collector failure must not interrupt runs, chat or navigation. Isolate collector jobs from execution jobs, propagate cancellation and keep all reads off the UI thread. If an owned process exits or its protocol becomes invalid, close its resources and restart only under the same bounded retry policy. Do not recover by scraping another source or starting a model turn. Invalid or incomplete responses never make an older reading fresh.

For event-only data, show "Updated by chat sessions" and the observation age. A Claude profile with no live session shows the last reading any chat reported for it. For pull sources, mark readings stale after two expected refresh intervals without a successful observation. Crossing a reported reset makes that meter expired even if the rest of the snapshot remains recent.

## Ownership in this repository

Keep the first implementation in the desktop modules unless a confirmed source needs runtime execution observations.

- `desktop/core/domain` owns the typed model and repository interface. It imports no provider JSON, subprocess API, or UI classes.
- `desktop/core/data` owns provider adapters, parsing, profile and identity validation, cache and refresh coordination. Reuse the existing process and diagnostics abstractions.
- `AppComponent` supplies the application scope, injected clock, diagnostics and shared repository. `WindowComponent` supplies the window's context selection. Window closure releases demand; application closure cancels reads and closes owned processes.
- The sidebar and popup render repository state through a presentation model. They do not read credentials, launch processes or schedule polling.

The current Codex chat transport uses `codex exec`. Add a small, owned app-server client for account reads rather than replacing chat transport for this card. It must initialize the protocol, tolerate unsupported capabilities, keep stdout for protocol messages and close its owned process on cancellation. Do not open a model thread or turn.

The current Claude stream decoder discards unrecognized event types. Capture confirmed usage observations before that information disappears, through a separate typed observation path. They are not chat text or tool activity. If runtime-launched agents also supply these signals, carry them through a narrow runtime port and launcher adapter. Do not read private runtime files from the desktop or create a second polling owner. Apply the runtime architecture and schema rules if that boundary changes.

Start with an in-memory cache for the app session. It avoids a new persisted account-data format and stale readings after restart. A later disk cache needs a versioned schema, expiry and account validation before display. The usage card's observation should work without a board database refresh.

Use existing sign-in without changing it. Do not scrape browser cookies, record tokens, or log raw provider payloads. A team Admin API key, if supported, needs an explicit configuration path and the project's secret-storage mechanism. Emit bounded, redacted diagnostics for unsupported versions, parsing failures, authentication failures, throttling and retained stale data. Record state transitions rather than repeating the same failure on every render.

## Implementation order and acceptance

1. Prove the documented acquisition paths on the supported installed CLI versions. Codex must return a read-only snapshot without starting a turn. For Claude, verify whether headless `/usage`, events or status-line output provide a supported source without risking a fallback model prompt. Do not parse terminal presentation text. For Cursor, establish personal versus team capabilities. Capture redacted fixtures and declare unsupported cases. Do not enable a collector until its identity and payload checks pass.
2. Build the typed model, adapters and shared coordinator. Test account isolation and partial updates before connecting the UI.
3. Connect context resolution to the existing sidebar and popup. Replace sample labels with response-derived labels, add source-specific refresh explanations and preserve the existing focus behavior.
4. Verify provider failures and lifecycle behavior, then exercise enabled collectors against real accounts through read-only requests. Verify explicit unavailable states for providers and account types without a reliable documented source.

Acceptance includes dynamic zero, one and many meters; different durations and billing cycles; correct pool selection; profile and account changes; phase and chat switches; missing resets; expired and stale observations; unsupported CLI versions; authentication failure; provider throttling; and a late response after switching accounts.

Tests should prove that windows share an in-flight read, hidden windows stop polling, cancellation closes owned processes, a partial event does not erase unrelated meters, and a reset does not fabricate restored allowance. Also cover a hung read, collector process exit, malformed payload, bounded restart attempts and continued execution when the collector fails. Use an injected clock and scheduler with virtual time. No sleeps or timing-based assertions.

Every enabled collector must provide a verified source, subject, unit and age. The card must explain unavailable data, and collector failures must leave execution and navigation working. V1 may ship with explicit unavailable states for any in-scope provider that lacks a reliable documented usage source.
