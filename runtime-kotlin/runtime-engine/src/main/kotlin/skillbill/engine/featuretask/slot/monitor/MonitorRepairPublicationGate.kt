package skillbill.engine.featuretask.slot.monitor

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.config.projectchecks.ProjectCheckCommand
import skillbill.contracts.config.projectchecks.ProjectCheckCommandSnapshot
import skillbill.engine.featuretask.lifecycle.branch.baseBranchOrDefault
import skillbill.engine.featuretask.model.execution.ValidationGateCyclePhase
import skillbill.engine.featuretask.model.phase.ValidationCommandLaunchTransport
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeBuildGateProjectConfig
import skillbill.engine.featuretask.validation.SavedProjectConfigRun
import skillbill.engine.featuretask.validation.projectchecks.QualityGateCommandRendering
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import java.nio.file.Path

/** The runtime's verdict on a settled monitor repair before it is committed and pushed. */
public sealed interface MonitorRepairPublicationVerdict {
  public data object Publishable : MonitorRepairPublicationVerdict

  /** The repair is not done; the agent relaunches with [correction] inside the retryable attempt budget. */
  public data class RepairAgain(val correction: String) : MonitorRepairPublicationVerdict

  /** The runtime cannot verify the repair, so it blocks without publishing. */
  public data class Refused(val reason: String) : MonitorRepairPublicationVerdict
}

/**
 * Verifies a settled monitor repair in [request.repoRoot] before publication: no unresolved conflict-marker block in
 * the files the branch changed since its merge base with [request.recordedBaseBranch] (or the default branch), and a
 * passing commands from [request.validationTransport].
 */
public fun interface MonitorRepairPublicationGate {
  public fun verify(request: MonitorRepairPublicationGateRequest): MonitorRepairPublicationVerdict
}

internal fun MonitorRepairPublicationVerdict.publicationBlockingCheck(): PhaseStepOutputCheck? =
  when (this) {
    MonitorRepairPublicationVerdict.Publishable -> null
    is MonitorRepairPublicationVerdict.RepairAgain -> PhaseStepOutputCheck.Retry(correction)
    is MonitorRepairPublicationVerdict.Refused -> PhaseStepOutputCheck.Block(reason)
  }

@Inject
public class RuntimeMonitorRepairPublicationGate(
  private val git: WorkflowGitOperations,
  private val runner: ValidationGateRunner,
  private val diagnostics: RuntimeDiagnostics,
) : MonitorRepairPublicationGate {
  override fun verify(request: MonitorRepairPublicationGateRequest): MonitorRepairPublicationVerdict =
    conflictVerdict(request.repoRoot, request.recordedBaseBranch) ?: savedValidationVerdict(request)

  private fun conflictVerdict(
    repoRoot: Path,
    recordedBaseBranch: String?,
  ): MonitorRepairPublicationVerdict? =
    when (val scan = conflictScan(repoRoot, recordedBaseBranch)) {
      is ConflictScan.Unreadable -> refused(scan.reason)
      is ConflictScan.Changed -> markerVerdict(repoRoot, scan)
    }

  private fun savedValidationVerdict(request: MonitorRepairPublicationGateRequest): MonitorRepairPublicationVerdict {
    val transport = request.validationTransport
    val snapshot =
      when (transport) {
        is ValidationCommandLaunchTransport.Commands -> transport.snapshot
        is ValidationCommandLaunchTransport.KnownEmpty -> transport.snapshot
        is ValidationCommandLaunchTransport.LegacyCommands, null -> null
      }
    val commands =
      when (transport) {
        is ValidationCommandLaunchTransport.Commands -> transport.snapshot.validationCommandList
        is ValidationCommandLaunchTransport.KnownEmpty -> emptyList()
        is ValidationCommandLaunchTransport.LegacyCommands -> transport.postRepairCommands
        null -> emptyList()
      }
    val category = snapshot?.validationCommands
    return when {
      transport == null ->
        refused(
          "Monitor repair publication requires an accepted validation command snapshot or legacy command source " +
            "for this repair cycle.",
        )
      snapshot != null && category == null ->
        refused("Required validation commands were not frozen for this repair or validation launch.")
      commands.isEmpty() -> {
        diagnostics.warning(
          "seam=monitor_repair_full_gate value_expected=saved_validation_commands value_actual=known_empty " +
            "cause=explicit_empty_validation repo_root=${request.repoRoot}",
        )
        MonitorRepairPublicationVerdict.Publishable
      }
      else -> runValidationCommands(request.repoRoot, snapshot, commands)
    }
  }

  private fun runValidationCommands(
    repoRoot: Path,
    snapshot: ProjectCheckCommandSnapshot?,
    commands: List<ProjectCheckCommand>,
  ): MonitorRepairPublicationVerdict =
    catchingNonCancellation {
      FeatureTaskRuntimeBuildGateProjectConfig.runSavedCommands(
        runner,
        repoRoot,
        snapshot,
        commands,
        ValidationGateCyclePhase.POST_REPAIR_VERIFY,
      )
    }.fold(
      onSuccess = { result ->
        when (result) {
          is SavedProjectConfigRun.Completed -> aggregateVerdict(result.result, commands)
          is SavedProjectConfigRun.LaunchBlocked -> refused(launchBlockedReason(result.failure, result.findings))
        }
      },
      onFailure = { error ->
        refused(
          "The runtime could not run the saved validation commands before publishing the monitor repair: " +
            (error.message ?: error.javaClass.simpleName),
        )
      },
    )

  private fun launchBlockedReason(
    failure: Throwable,
    findings: List<ValidationGateFinding>,
  ): String =
    buildString {
      append("The runtime could not run the saved validation commands before publishing the monitor repair: ")
      append(failure.message ?: failure.javaClass.simpleName)
      findings.take(MAX_CORRECTION_FINDINGS).forEach { finding ->
        val location = finding.location?.let { " ($it)" }.orEmpty()
        append("\n- ${finding.module}: ${finding.ruleOrTestId}: ${finding.message}$location".take(MAX_FINDING_CHARS))
      }
      if (findings.size > MAX_CORRECTION_FINDINGS) append("\n- and ${findings.size - MAX_CORRECTION_FINDINGS} more")
    }

  private fun aggregateVerdict(
    result: ValidationGateRunResult,
    commands: List<ProjectCheckCommand>,
  ): MonitorRepairPublicationVerdict =
    when {
      result.outcome == ValidationGateRunOutcome.REJECTED_ZERO_WORK ->
        refused("A saved validation command ran no work, so the monitor repair cannot be verified.")
      result.outcome != ValidationGateRunOutcome.PASSED || result.exitCode != 0 ->
        MonitorRepairPublicationVerdict.RepairAgain(gateCorrection(result, commands))
      else -> MonitorRepairPublicationVerdict.Publishable
    }

  private fun conflictScan(
    repoRoot: Path,
    recordedBaseBranch: String?,
  ): ConflictScan {
    val head =
      (git.headCommitSha(repoRoot) as? WorkflowGitOperationResult.Ok)?.value?.trim()?.takeIf(String::isNotBlank)
        ?: return ConflictScan.Unreadable(
          "Monitor could not read HEAD to scan the repair for unresolved merge conflicts.",
        )
    val mergeBase = mergeBase(repoRoot, recordedBaseBranch)
    val listings =
      listOf(
        mergeBase?.let { git.runtimePhaseChangedPathsBetweenCommits(repoRoot, it, head) }
          ?: WorkflowGitNameListResult.Listed(emptyList()),
        git.repositoryOwnedPaths(repoRoot),
      )
    val failure = listings.filterIsInstance<WorkflowGitNameListResult.Failed>().firstOrNull()
    return if (failure != null) {
      ConflictScan.Unreadable("Monitor could not list the repair's changed files to scan them: ${failure.error}")
    } else {
      val paths = listings.filterIsInstance<WorkflowGitNameListResult.Listed>().flatMap { it.names }
      ConflictScan.Changed(mergeBase ?: head, paths.distinct().sorted())
    }
  }

  private fun markerVerdict(
    repoRoot: Path,
    scan: ConflictScan.Changed,
  ): MonitorRepairPublicationVerdict? {
    if (scan.paths.isEmpty()) return null
    val contents = git.scopedPathContentsAgainstBase(repoRoot, scan.baseRevision, scan.paths)
    if (contents.status != WorkflowGitOperationStatus.OK) {
      return refused("Monitor could not read the repair's changed files to scan for conflicts: ${contents.error}")
    }
    val marked =
      contents.pairs
        .filter { ConflictMarkerBlocks.present(it.headContent) && !ConflictMarkerBlocks.present(it.baseContent) }
        .map { it.headPath }
    return marked
      .takeIf(List<String>::isNotEmpty)
      ?.let { MonitorRepairPublicationVerdict.RepairAgain(conflictCorrection(it)) }
  }

  private fun mergeBase(
    repoRoot: Path,
    recordedBaseBranch: String?,
  ): String? {
    val base = git.baseBranchOrDefault(repoRoot, recordedBaseBranch)
    val revisions = listOf("refs/remotes/origin/$base", base)
    val found =
      revisions.firstNotNullOfOrNull { revision ->
        (git.mergeBaseWithHead(repoRoot, revision) as? WorkflowGitOperationResult.Ok)
          ?.value
          ?.trim()
          ?.takeIf(String::isNotBlank)
      }
    if (found == null) {
      diagnostics.warning(
        "record_kind=degradation seam=monitor_repair_conflict_scan value_expected=branch_changes_since_merge_base " +
          "value_used=working_tree_changes_only cause=no merge base with ${revisions.joinToString(" or ")} " +
          "repo_root=$repoRoot",
      )
    }
    return found
  }

  private fun refused(reason: String): MonitorRepairPublicationVerdict.Refused =
    MonitorRepairPublicationVerdict.Refused(reason)
}

internal object ConflictMarkerBlocks {
  private const val MARKER_WIDTH = 7
  private val SEPARATOR = "=".repeat(MARKER_WIDTH)

  fun present(text: String?): Boolean {
    if (text == null) return false
    var opened = false
    var separated = false
    for (raw in text.lineSequence()) {
      val line = raw.trimEnd('\r')
      when {
        isMarker(line, '<') -> {
          opened = true
          separated = false
        }
        opened && line == SEPARATOR -> separated = true
        separated && isMarker(line, '>') -> return true
      }
    }
    return false
  }

  private fun isMarker(
    line: String,
    char: Char,
  ): Boolean =
    line.length >= MARKER_WIDTH &&
      line.take(MARKER_WIDTH).all { it == char } &&
      (line.length == MARKER_WIDTH || line[MARKER_WIDTH] == ' ')
}

private sealed interface ConflictScan {
  data class Unreadable(val reason: String) : ConflictScan

  data class Changed(
    val baseRevision: String,
    val paths: List<String>,
  ) : ConflictScan
}

private const val MAX_CORRECTION_PATHS = 40
private const val MAX_CORRECTION_FINDINGS = 40
private const val MAX_FINDING_CHARS = 300
private const val MAX_OUTPUT_TAIL_CHARS = 4000

private fun conflictCorrection(paths: List<String>): String =
  buildString {
    append("The runtime refused to publish this monitor repair: these files still contain unresolved merge-conflict ")
    append("markers (a <<<<<<< / ======= / >>>>>>> block). Resolve every conflict so each file keeps both ")
    append("sides' intended changes, remove all markers, then rerun the project's full checks. Nothing is published ")
    append("until no markers remain and the runtime's saved validation commands pass.")
    paths.take(MAX_CORRECTION_PATHS).forEach { append("\n- ").append(it) }
    if (paths.size > MAX_CORRECTION_PATHS) append("\n- and ${paths.size - MAX_CORRECTION_PATHS} more")
  }

private fun gateCorrection(
  result: ValidationGateRunResult,
  commands: List<ProjectCheckCommand>,
): String =
  buildString {
    append("The runtime ran the admitted saved validation commands before publishing this monitor repair and ")
    append("at least one failed with exit code ${result.exitCode}. Fix every failure below, including failures ")
    append("unrelated to the original CI check, and rerun the saved list until they all pass.")
    append("\nRerun these saved command records in listed order, using each working directory:\n")
    append(QualityGateCommandRendering.markdownCodeBlock(QualityGateCommandRendering.renderCommands(commands)))
    val findings = result.findings
    findings.take(MAX_CORRECTION_FINDINGS).forEach { finding ->
      val location = finding.location?.let { " ($it)" }.orEmpty()
      append("\n- ")
      append("${finding.module}: ${finding.ruleOrTestId}: ${finding.message}$location".take(MAX_FINDING_CHARS))
    }
    if (findings.size > MAX_CORRECTION_FINDINGS) append("\n- and ${findings.size - MAX_CORRECTION_FINDINGS} more")
    val tail = result.stdout.takeLast(MAX_OUTPUT_TAIL_CHARS).trim()
    if (tail.isNotEmpty()) append("\n\nGate output tail:\n").append(tail)
  }
