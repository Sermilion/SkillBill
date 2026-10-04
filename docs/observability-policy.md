# Observability Policy

Every fallback, degradation, and swallowed failure emits a record. A silent
fallback is a defect: it produces the symptom of a bug with none of the evidence.

Emit an observability event, structured log line, or telemetry field at each of
these seams:

- a `?:`, `takeIf`, `getOrNull`, `orEmpty`, or default-value path that
  substitutes a narrower or wider scope than the caller asked for
- a `runCatching` or `catch` that continues instead of rethrowing
- a retry, timeout, cap, truncation, or sampling decision
- a capability that resolves to absent and is skipped
- spec-intent resolution that records `spec_context: none` (`no_spec_found`, `ambiguous_match`, `not_applicable_scope`) or falls through from an unreadable decomposition manifest to branch-derived glob search; records carry reason, rung, and resolved path only, never spec body
- a skipped adjudication stage, a review worker that did not return usable output, and a stage that ended without a reached boundary; each emits `skillbill_review_stage_degradation` with seam, expected, actual, and a closed reason, carrying `review_run_id` only. Worker failure is reported by cause, not as one bucket: `worker_process_failed` (spawned and died, was interrupted, or exited with an unknown status), `worker_timed_out`, `worker_output_unusable` (returned, but its verification or adjudication output did not parse), `worker_launch_budget_exceeded` (a launch or retained output over `max_lane_launch_bytes`), and `worker_launch_or_return_failed` for an unsupported-agent refusal, which names no failure mode of its own. A review pass that never published a disposition — a failed, interrupted, truncated, or empty-output lane — is `review_pass_output_absent`, separate from every worker cause above, because nothing was verified and nothing failed while verifying. That record is the only thing the verification stage produces in that case: the stage boundary stays unreached, existing findings and durable verdicts are preserved, and the run is never reported as verified or `NO_FINDINGS`. Classification is by exact rejection reason: a reason no cause recognizes emits no worker-failure record at all, because the selector cannot tell it apart from an ordinary rejected verdict. Adding a rejection reason means adding its cause here, or the failure goes unreported rather than being attributed to a cause nobody checked
- a degradation record that cannot be written lands as a bounded local diagnostic through the optional-write seam. It never marks a stage reached, never clears a finding, and never replaces the primary error, so a failed diagnostic write cannot fabricate clearance
- a legacy-record migration, quarantine, or regeneration
- a runtime refusal or migration normalization; records use `record_kind: refusal` or
  `record_kind: migration` and carry the seam, value used, expected value, and bounded cause
- a reconciliation that repairs drift between durable state and disk
- a rejected-output diagnostic write that cannot land: a same-identity conflict, a
  permission refusal, a corrupt or oversized row, an unavailable repository, or a
  schema violation. The rejection that triggered the write is what the run reports —
  the diagnostic failure never replaces it and never fails the run. A bounded,
  payload-free signal is appended to the workflow's own durable artifacts naming the
  operation, failure class, conflicting key, phase, attempt, repair turn, and
  generation; it carries no agent bytes. A caller-construction defect
  (`InvalidRequest`, `InvalidConfiguration`) is a loud-fail, not a degradation
- checkpoint-ref prune: `FeatureTaskRuntimeCheckpointRefPrune.pruneSubtaskCheckpointRefs`
  when listing or deleting a ref under `refs/skill-bill/checkpoints/` fails, or when
  pruning is skipped because `commit_sha` is still blank
- gate JVM resolution: `GateJvmResolver.resolve` records the guard branch taken
  (`skill_bill_java_home`, `inherited_java_home`, `path_java`, `scan`, `unresolved`), the
  `JAVA_HOME` handed to the child, and every `JAVA_HOME`, `SKILL_BILL_JAVA_HOME`, or `PATH`
  entry dropped because it pointed inside the runtime image root.
  `unresolved` is a typed error at the runtime-run gate and a recorded
  degradation with `JAVA_HOME` left absent at the agent-run launch surface; the `unresolved`
  record names the rejected candidate and the required major so the degraded launch carries the
  same attribution as the typed error
- gate JVM startup failure: a gate command that exits non-zero, parses no findings, and reports a
  JVM-initialization failure raises `GateJvmStartupFailureException` naming the resolved Java home
  instead of minting an `unparseable_gate_failure` finding, so an unusable gate JVM surfaces as an
  environment defect rather than as a repair turn no source edit can clear
- platform-pack `contract_version` leniency: `PlatformPackSchemaValidator.validate`
  when a caller enumerates with `enforceContractVersion=false` (reconcile's LOCAL side and
  installed-workspace baseline status) and a stale `const` violation is tolerated instead of
  raising `ContractVersionMismatchError`
- worktree edit journal measure: `WorktreeEditJournalWriter.observe` when
  `worktreeNumstat` is non-OK records
  `seam=worktree_edit_journal_measure value_expected=numstat value_used=skipped` and skips the tick
- worktree edit journal persist: the same observe path when append/trim fails records
  `seam=worktree_edit_journal_persist value_expected=persisted_tick value_used=failed` and returns
  without rethrowing (except cooperative cancellation)
- worktree edit journal cap: when `trimToCap` drops rows records
  `seam=worktree_edit_journal_cap value_expected=rows_within_cap value_used=dropped_oldest_ticks`
  with `dropped_rows`
- review diff queries: `FileSystemDiffResolver` logs a WARNING with `seam=diff_resolver_query`,
  the query, value used, value expected, and cause for every unavailable result (timeout, launch
  failure, rejected exit, over-cap output, I/O failure, malformed index record, PR diff fallback
  to `gh pr diff`) and for a temp-file cleanup failure. Unavailable is `null`; an empty string or
  list is a successful empty result. Shared review evidence derivation records
  `seam=shared_review_evidence_derive` when an unavailable diff yields no evidence, and
  `seam=shared_review_evidence_parse` when a diff fails to parse

Each record names the seam, the value actually used, the value that was expected,
and why the substitution happened. A fallback that cannot be attributed to a
specific cause is a loud-fail, not a log line.

Standalone validation requires a dominant platform pack with a declared
`validation_gate` and both collect-all commands. Missing declarations or required
commands block with a bounded diagnostic after dominant-pack resolution.
The runtime never substitutes an agent-run gate or an empty successful receipt.

Required start and briefing writes reject with their write kind, phase, and
attempt. The attempt boundary records that rejection before returning a blocked
outcome. A secondary terminal-write or diagnostic failure must not replace the
original reason. Cancellation still propagates. Review and planning fan-out
must acknowledge start persistence before preparation or child launch. Audit
acknowledges its start but deliberately omits durable briefing storage.

Gate receipt recovery preserves the original output, attempts, checkpoints, and
finalization records. Unsupported or incoherent evidence blocks before the run
can regenerate a producer or repeat commit and push. Preserve the run and inspect
its diagnostics with a compatible runtime or a separately reviewed recovery
mapping. Do not delete records to bypass evidence rejection.

Prefer loud-fail over log-and-continue whenever the substituted value changes a
contract the caller depends on — scope bounds, review deltas, staged path
inventories, and durable identity are contracts. Log-and-continue is for
degradations the caller can still trust.

Bounded output rules still apply: records carry counts, identities, and sanitized
labels, never raw payloads, diff hunks, or unbounded child output.

## An unmeasured quantity is declared absent, never emitted as zero

A metric the runtime did not measure is not `0`, not `false`, and not an empty list.
Those are legitimate measured outcomes, and emitting one for a quantity nobody
counted makes an unknown indistinguishable from a real result — a run that never
recorded a review-fix cap reads exactly like a run whose cap was never spent.

Every such field is a pair: the value, and an availability token from
`TelemetryMeasurementAvailability`.

| Token | Meaning |
| --- | --- |
| `measured` | The runtime counted it. The value is present and trustworthy. |
| `unavailable_no_durable_state` | Nothing durable recorded it — typically a row written before the column existed. Backfill is not possible, so no value is emitted. |
| `unavailable_unsupported` | No measurement applies at this seam. A process failure and a reconciliation rejection spend no output-gate correction budget, so neither reports one. |
| `unavailable_incomplete` | Measurement started but did not reach a countable state. |
| `unknown` | The availability itself could not be resolved. |

When the token is anything but `measured`, the value key is emitted as `null` or
omitted — never defaulted. Consumers filter on the availability token first and
treat any non-`measured` row as outside the denominator, not as a zero inside it.

Migrations that add these columns are nullable with no backfill, for the same
reason: a historical row has no recoverable value, and inventing one would convert
a gap in the record into a fabricated outcome.

Where a count could be read at more than one grain, the payload states the grain it
used rather than leaving consumers to guess — `audit_gap_measurement_grain` carries
`audit_gap_rounds_per_run`.

Rates follow the same rule. A run the stale reconciler closed was never observed
reaching a terminal, so it is not evidence about how runs end: `observed_runs` is
the denominator for every rate, `reconciler_closed_runs` is published on its own,
and a terminal row states which it is through `completion`
(`operator_completed` or `reconciler_stale`).

## Correlation identifiers join records without exposing an identity

A lifecycle record, the rejections of the same run, and its diagnostics are only
useful together, so each carries the identifiers needed to join them:
`redacted_workflow_id`, `goal_parent_workflow_id`, and `goal_subtask_id`, alongside
`correlation_availability`.

- The workflow id and goal parent id are redacted at the emission seam with the same
  salted hash the issue key uses, so anonymous mode keeps the join without ever
  putting a raw identifier or an issue key substring on the wire.
- A standalone run has no goal parent and no subtask id. Those fields are absent —
  absence is the fact, not a sentinel.
- A run whose workflow id the runtime does not know reports
  `correlation_availability: unknown` and emits no id.
- Delivery identity (`$insert_id`) and `skill_bill_version` stay owned by the
  envelope and the transport. Payloads do not restate them; a second authority for
  event identity is how two records of one event come to disagree.

## Telemetry delivery health records on the row, never in the queue

Telemetry delivery degradation is the one seam where emitting a telemetry event is
forbidden. The outbox drain is what failed; enqueuing a diagnostic into the same
store feeds the drain it cannot complete, and `autoSync` runs at every CLI
completion and every MCP tool call, so the queue grows faster than it drains.

The record lives on the failing row instead:

- `last_error` carries a bounded, actionable message that distinguishes *rejected*
  (the receiver will not accept this payload, recorded with the relay's status code
  and its returned reason, truncated to a bounded length), *unconfirmed* (the receiver may
  already hold the batch — a timeout, an aborted upstream, or a relay 502 after
  possible acceptance), and *local acknowledgement failed* (the batch was accepted
  but the local mark-synced write did not land)
- `delivery_attempts` counts the failures the receiver confirmed — a rejection, and
  an accepted send whose local acknowledgement did not land. A row past the budget
  stays queued but is no longer claimed, so a poison row cannot replay forever
- an *unconfirmed* failure records `last_error` and releases the claim without
  spending budget. It is a transport verdict, not a receiver verdict, and `autoSync`
  fires often enough that charging it would block the whole queue permanently after
  a few offline minutes, with no way back once connectivity returned
- `skill-bill telemetry status` reports `pending_events`, `latest_error`, and
  `blocked_events`; a drain that ends with blocked rows reports `failed`, never
  `synced`, so budget exhaustion is never silent
- a blocked row has no redelivery path. There is no retry command, so the failure
  message names what an operator can actually do — read the row through
  `telemetry status`, or discard it with `skill-bill telemetry clear` — instead of
  promising an automatic retry that never arrives
- a drain that claimed nothing because a concurrent drain holds every queued row
  reports `noop` with those rows still counted in `pending_events`, never `synced`
  with zero delivered events, so a stalled drain does not read as a clean one
- a client failure of any type, not only a transport `IOException`, records
  `last_error` and releases the claim. An escaping failure left the batch claimed
  with no recorded cause until the lease expired

### Reproduced cause and what stayed unconfirmed

The reproduction fixtures cover three candidate causes of whole-batch resend. Two
reproduce deterministically against a real SQLite file and a fake transport:

1. **Unclaimed concurrent drain.** `listPending` handed identical unclaimed rows to
   every drainer, so two drains sent the same batch. Repaired by an explicit claim
   with an expiring lease.
2. **Accepted send, failed local acknowledgement.** The drain looped on
   `listPending` and re-read the same still-pending rows, resending them on the next
   pass without bound. Repaired by claiming before sending, counting progress only
   after a durable mark-synced, and bounding the invocation by the pending snapshot.

The third — the receiver accepting a batch while the sender loses the
acknowledgement — is covered by a fake-transport fixture that proves the *sender*
keeps one recoverable entry with its original identity. It does **not** prove what
the receiver did with the retry: that depends on the provider's `$insert_id`
deduplication window, which is finite. Whether any specific historical duplicate
originated from this path rather than from cause 1 or 2 stays unconfirmed; the
fixtures pin the sender-side behavior, not a retrospective attribution.

### Counting historical telemetry

Historical analysis of already-ingested events is read-only. Do not delete,
rewrite, or reclassify past events to make a count look clean.

- Events ingested before durable identity existed carry no `$insert_id`. They cannot
  be deduplicated retroactively and must be reported with that caveat attached.
- After the change, duplicate suppression is bounded by the receiver's finite
  deduplication window. A retry outside that window is a real second row upstream.
  Report deduplicated counts as best-effort, never as exactly-once.
- Stale or missing data is stale or missing. A gap in a series is not a proven zero,
  and an absent event is not a proven non-occurrence; say which it is rather than
  converting either into an outcome.
