package skillbill.engine.featuretask.slot.monitor

import me.tatarka.inject.annotations.Inject
import skillbill.config.model.applyValidationGateGradleWrapper
import skillbill.engine.featuretask.lifecycle.branch.baseBranchOrDefault
import skillbill.engine.featuretask.model.execution.ValidationGateCyclePhase
import skillbill.engine.featuretask.slot.PhaseStepOutputCheck
import skillbill.engine.featuretask.validation.ValidationGateResolver
import skillbill.engine.featuretask.validation.model.ValidationGateResolution
import skillbill.engine.featuretask.validation.validationGateArgv
import skillbill.error.core.catchingNonCancellation
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateFindingParseMode
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode
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
 * Verifies a settled monitor repair in [repoRoot] before publication: no unresolved conflict-marker block in the
 * files the branch changed since its merge base with [recordedBaseBranch] (or the default branch), and a passing
 * full validation gate of the dominant platform pack.
 */
public fun interface MonitorRepairPublicationGate {
  public fun verify(
    repoRoot: Path,
    recordedBaseBranch: String?,
  ): MonitorRepairPublicationVerdict
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
  private val resolver: ValidationGateResolver,
  private val runner: ValidationGateRunner,
  private val repoLocalConfig: RepoLocalConfigPort,
  private val diagnostics: RuntimeDiagnostics,
) : MonitorRepairPublicationGate {
  override fun verify(
    repoRoot: Path,
    recordedBaseBranch: String?,
  ): MonitorRepairPublicationVerdict = conflictVerdict(repoRoot, recordedBaseBranch) ?: fullGateVerdict(repoRoot)

  private fun conflictVerdict(
    repoRoot: Path,
    recordedBaseBranch: String?,
  ): MonitorRepairPublicationVerdict? =
    when (val scan = conflictScan(repoRoot, recordedBaseBranch)) {
      is ConflictScan.Unreadable -> refused(scan.reason)
      is ConflictScan.Changed -> markerVerdict(repoRoot, scan)
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

  private fun fullGateVerdict(repoRoot: Path): MonitorRepairPublicationVerdict {
    val tracked =
      when (val listed = git.trackedPaths(repoRoot)) {
        is WorkflowGitNameListResult.Listed -> listed.names
        is WorkflowGitNameListResult.Failed ->
          return refused("Monitor could not list tracked files to select the validation gate: ${listed.error}")
      }
    return when (val resolution = resolver.resolve(tracked)) {
      is ValidationGateResolution.Declared -> runFullGate(repoRoot, resolution)
      is ValidationGateResolution.Absent ->
        agentChecksOnly(repoRoot, "dominant pack '${resolution.routedPackSlug ?: "none"}' declares no validation gate")
      is ValidationGateResolution.Incompatible -> agentChecksOnly(repoRoot, resolution.reason)
    }
  }

  private fun runFullGate(
    repoRoot: Path,
    resolution: ValidationGateResolution.Declared,
  ): MonitorRepairPublicationVerdict {
    val declaration = resolution.declaration
    val packArgv =
      validationGateArgv(declaration, ValidationGateCyclePhase.POST_REPAIR_VERIFY)
        .ifEmpty { validationGateArgv(declaration, ValidationGateCyclePhase.INITIAL_DISCOVERY) }
    if (packArgv.isEmpty()) {
      return agentChecksOnly(repoRoot, "pack '${resolution.packSlug}' declares no full validation gate command")
    }
    val argv =
      catchingNonCancellation {
        val config = repoLocalConfig.readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot)).config
        applyValidationGateGradleWrapper(packArgv, config.validationGate.gradleWrapper)
      }.getOrElse { error ->
        return refused("Monitor could not read the repository's validation gate settings: ${error.message.orEmpty()}")
      }
    val command = argv.joinToString(" ")
    val result =
      catchingNonCancellation { runner.run(fullGateRequest(repoRoot, argv, declaration)) }.getOrElse { error ->
        return refused(
          "The runtime could not run the full validation gate `$command` before publishing the monitor repair: " +
            (error.message ?: error.javaClass.simpleName),
        )
      }
    return when {
      result.outcome == ValidationGateRunOutcome.PASSED && result.exitCode == 0 ->
        MonitorRepairPublicationVerdict.Publishable
      result.outcome == ValidationGateRunOutcome.REJECTED_ZERO_WORK ->
        refused("The full validation gate `$command` ran no work, so the monitor repair cannot be verified.")
      else -> MonitorRepairPublicationVerdict.RepairAgain(gateCorrection(command, result))
    }
  }

  private fun fullGateRequest(
    repoRoot: Path,
    argv: List<String>,
    declaration: ValidationGateDeclaration,
  ): ValidationGateRunRequest =
    ValidationGateRunRequest(
      repoRoot = repoRoot,
      argv = argv,
      cacheMode = ValidationGateCacheMode.FORCED_FULL,
      declaration = declaration,
      terminalVerifying = true,
      findingParseMode = ValidationGateFindingParseMode.COLLECT_ALL,
    )

  private fun agentChecksOnly(
    repoRoot: Path,
    cause: String,
  ): MonitorRepairPublicationVerdict {
    diagnostics.warning(
      "record_kind=degradation seam=monitor_repair_full_gate value_expected=runtime_full_validation_gate " +
        "value_used=agent_reported_checks cause=$cause repo_root=$repoRoot",
    )
    return MonitorRepairPublicationVerdict.Publishable
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
    append("until no markers remain and the runtime's full validation gate passes.")
    paths.take(MAX_CORRECTION_PATHS).forEach { append("\n- ").append(it) }
    if (paths.size > MAX_CORRECTION_PATHS) append("\n- and ${paths.size - MAX_CORRECTION_PATHS} more")
  }

private fun gateCorrection(
  command: String,
  result: ValidationGateRunResult,
): String =
  buildString {
    append("The runtime ran the full validation gate `$command` before publishing this monitor repair and it ")
    append("failed with exit code ${result.exitCode}. Fix every failure below, including failures unrelated to ")
    append("the original CI check, and rerun the gate until it passes. Nothing is published until it does.")
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
