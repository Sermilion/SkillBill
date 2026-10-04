package skillbill.cli.review

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import me.tatarka.inject.annotations.Inject
import skillbill.application.review.model.ReviewSnapshotPruneResult
import skillbill.application.review.service.ReviewService
import skillbill.application.review.snapshot.ReviewSnapshotPruneService
import skillbill.cli.kernel.cli.CliOutput
import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.cli.DocumentedCliCommand
import skillbill.cli.kernel.cli.formatOption
import skillbill.cli.kernel.cli.toCliNumberedFindingsPresentation
import skillbill.cli.kernel.cli.toCliTriagePresentation
import skillbill.cli.model.CliFormat
import skillbill.cli.model.CliRunInputs

@Inject
class FeatureStatsCommands(
  featureVerifyStatsCommand: FeatureVerifyStatsCommand,
  featureTaskStatsCommand: FeatureTaskStatsCommand,
  featureTaskRuntimeStatsCommand: FeatureTaskRuntimeStatsCommand,
  goalStatsCommand: GoalStatsCommand,
) {
  val commands =
    listOf(
      featureVerifyStatsCommand,
      featureTaskStatsCommand,
      featureTaskRuntimeStatsCommand,
      goalStatsCommand,
    )
}

@Inject
class ReviewTopLevelCommands(
  importReviewCommand: ImportReviewCommand,
  recordFeedbackCommand: RecordFeedbackCommand,
  triageCommand: TriageCommand,
  statsCommand: ReviewStatsCommand,
  pruneSnapshotsCommand: PruneReviewSnapshotsCommand,
  featureStatsCommands: FeatureStatsCommands,
) {
  val commands =
    listOf(
      importReviewCommand,
      recordFeedbackCommand,
      triageCommand,
      statsCommand,
      pruneSnapshotsCommand,
    ) + featureStatsCommands.commands
}

@Inject
class ImportReviewCommand(
  private val service: ReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand("import-review", "Import a review output file or stdin into the local SQLite store.") {
  private val input by argument(help = "Path to review text, or '-' for stdin.").default("-")
  private val format by formatOption()

  override fun run() {
    val stdinForImport = if (input == "-") state.wholeStdinText() else null
    state.complete(service.importReview(input, stdinText = stdinForImport).toCliMap(), format)
  }
}

@Inject
class RecordFeedbackCommand(
  private val service: ReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand("record-feedback", "Record explicit feedback events for findings in an imported review run.") {
  private val runId by option("--run-id", help = "Imported review run id.").required()
  private val event by option("--event", help = "Canonical finding outcome to record.").required()
  private val findings by option("--finding", help = "Finding id to update. Repeat for multiple findings.").multiple(
    required = true,
  )
  private val note by option("--note", help = "Optional note for the recorded feedback event.").default("")
  private val format by formatOption()

  override fun run() {
    state.complete(service.recordFeedback(runId, event, findings, note).toCliMap(), format)
  }
}

@Inject
class TriageCommand(
  private val service: ReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand(
    "triage",
    "Show numbered findings for a review run and record triage decisions by number.",
  ) {
  private val runId by option("--run-id", help = "Imported review run id.").required()
  private val decisions by option(
    "--decision",
    help = "Triage entry like '1 fix', '2 skip - intentional', or '3 accept - good catch'.",
  ).multiple()
  private val listOnly by option("--list", help = "Show numbered findings without recording decisions.")
    .flag(default = false)
  private val format by formatOption()

  override fun run() {
    val result = service.triage(runId, decisions, listOnly)
    val payload = result.toCliMap()
    when {
      format == CliFormat.JSON -> state.complete(payload, format)
      result.findings.isNotEmpty() ->
        state.completeText(
          CliOutput.numberedFindings(result.toCliNumberedFindingsPresentation(runId)),
          payload,
        )
      else -> state.completeText(CliOutput.triageResult(result.toCliTriagePresentation(runId)), payload)
    }
  }
}

@Inject
class ReviewStatsCommand(
  private val service: ReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand(
    "stats",
    "Show aggregate or per-run review acceptance metrics, including per-stage verdict " +
      "distribution and refutation rate.",
  ) {
  private val runId by option("--run-id", help = "Optional review run id to scope stats to one review.")
  private val format by formatOption()

  override fun run() {
    state.complete(service.reviewStats(runId).toCliMap(), format)
  }
}

@Inject
class PruneReviewSnapshotsCommand(
  private val service: ReviewSnapshotPruneService,
  private val state: CliRunState,
) : DocumentedCliCommand(
    "prune-snapshots",
    "List and optionally delete ~/.skill-bill/review-metrics.<label>.db snapshots. " +
      "Retention policy: snapshots are operator artifacts with no expiry. Nothing creates, rotates, " +
      "or deletes them automatically, so they accumulate until you prune them here. This command is " +
      "the only code path that removes one. It defaults to a dry run that lists candidates and " +
      "deletes nothing; pass --confirm to actually delete. The live review-metrics.db is never a " +
      "candidate.",
  ) {
  private val confirm by option(
    "--confirm",
    help = "Actually delete the listed snapshots. Without this flag nothing is deleted.",
  ).flag(default = false)
  private val format by formatOption()

  override fun run() {
    val result = service.prune(confirm)
    val payload = result.toCliMap()
    if (format == CliFormat.JSON) {
      state.complete(payload, format)
    } else {
      state.completeText(CliOutput.reviewSnapshotPrune(result), payload)
    }
  }
}

@Inject
class FeatureVerifyStatsCommand(
  private val service: ReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand("verify-stats", "Show aggregate bill-feature-verify metrics.") {
  private val format by formatOption()

  override fun run() {
    state.complete(service.featureVerifyStats().toCliMap(), format)
  }
}

@Inject
class FeatureTaskStatsCommand(
  private val service: ReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand("feature-task-stats", "Show aggregate feature-task metrics.") {
  private val format by formatOption()

  override fun run() {
    state.complete(service.featureTaskRuntimeStats().toCliMap(), format)
  }
}

@Inject
class FeatureTaskRuntimeStatsCommand(
  private val service: ReviewService,
  private val state: CliRunState,
  private val inputs: CliRunInputs,
) : DocumentedCliCommand(
    "runtime-stats",
    "Deprecated alias for feature-task-stats. Use feature-task-stats; behavior is unchanged.",
  ) {
  override val hiddenFromHelp: Boolean = true

  private val format by formatOption()

  override fun run() {
    inputs.liveStderr(
      "runtime-stats is a deprecated alias for feature-task-stats. " +
        "Use feature-task-stats; behavior is unchanged.\n",
    )
    state.complete(service.featureTaskRuntimeStats().toCliMap(), format)
  }
}

@Inject
class GoalStatsCommand(
  private val service: ReviewService,
  private val state: CliRunState,
) : DocumentedCliCommand("goal-stats", "Show aggregate decomposed-goal run metrics.") {
  private val format by formatOption()

  override fun run() {
    state.complete(service.goalStats().toCliMap(), format)
  }
}

internal fun ReviewSnapshotPruneResult.toCliMap(): Map<String, Any?> =
  linkedMapOf(
    "live_db_path" to liveDbPath,
    "confirmed" to confirmed,
    "candidate_count" to candidates.size,
    "candidate_bytes" to candidateBytes,
    "deleted_count" to deleted.size,
    "reclaimed_bytes" to reclaimedBytes,
    "candidates" to
      candidates.map { snapshot ->
        linkedMapOf<String, Any?>(
          "path" to snapshot.path.toString(),
          "label" to snapshot.label,
          "size_bytes" to snapshot.sizeBytes,
          "last_modified" to snapshot.lastModified,
          "deleted" to (snapshot in deleted),
        )
      },
  )
