package skillbill.engine.featuretask.qa.execution

import me.tatarka.inject.annotations.Inject
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.model.execution.QaCallerBaseline
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.model.execution.QaExpectedIdentity
import skillbill.engine.featuretask.model.execution.QaPolicy
import skillbill.engine.featuretask.model.execution.QaSkipReason
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolution
import skillbill.engine.featuretask.qa.capability.QaCapabilityResolver
import skillbill.engine.featuretask.qa.report.QaRetainedResource
import skillbill.engine.operation.core.worktreeBaseline
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.qa.isQaDeviceLeaseFailure
import skillbill.error.featuretask.qa.isQaFailure
import skillbill.error.featuretask.qa.qaDeviceLeaseReason
import skillbill.error.featuretask.qa.qaRepositoryUnreadable
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.qa.QaDeviceLeasePort
import skillbill.ports.qa.QaWorkspacePort
import skillbill.ports.qa.model.QaLeaseReleaseOutcome
import skillbill.ports.qa.model.QaWorkspace
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Path

@Inject
class QaExecutionLifecycle(
  private val workspaces: QaWorkspacePort,
  private val leases: QaDeviceLeasePort,
  private val capabilities: QaCapabilityResolver,
  private val checkouts: QaCheckouts,
  private val gitOperations: WorkflowGitOperations,
  private val diagnostics: RuntimeDiagnostics,
  private val identifiers: IdentifierGeneratorPort,
) {
  internal fun <T> withExecution(
    repoRoot: Path,
    policy: QaPolicy,
    invocationId: String = "qa-${identifiers.randomToken()}",
    revision: String? = null,
    execute: (QaExecutionContext) -> T,
  ): QaExecutionResult<T> {
    val baseline = gitOperations.worktreeBaseline(repoRoot, ::cannotStart)
    val caller = QaCallerBaseline(baseline.head, treeIdentity(repoRoot), baseline.status)
    val testedTree = if (revision == null) caller.treeIdentity else revisionTreeIdentity(repoRoot, revision)
    val workspace = workspaces.open(repoRoot, invocationId)
    val expected = QaExpectedIdentity(revision ?: baseline.head, testedTree, workspace.evidencePrefix)
    var retained = emptyList<QaRetainedResource>()
    var checkout: Path? = null
    val warnings = mutableListOf<String>()
    val workspaceOwner = AutoCloseable { retained = retained + closeWorkspace(workspace) }
    val leaseOwner = AutoCloseable { retained = retained + releaseLeases(repoRoot, invocationId) }
    val checkoutOwner = AutoCloseable { checkout?.let { retained = retained + checkouts.close(repoRoot, it) } }
    val value =
      workspaceOwner.use {
        leaseOwner.use {
          checkoutOwner.use {
            val target =
              target(repoRoot, policy, warnings) {
                checkouts.open(repoRoot, workspace, expected.headRevision, copyWorkingState = revision == null)
              }
            checkout = (target as? QaExecutionTarget.Supported)?.checkout
            val result = execute(QaExecutionContext(workspace, expected, target, policy, warnings.toList(), caller))
            warnings += callerChanges(repoRoot, caller)
            result
          }
        }
      }
    return QaExecutionResult(value, retained, expected, warnings)
  }

  internal fun outputWarnings(
    repoRoot: Path,
    qa: QaExecutionContext,
  ): List<String> = qa.preparationWarnings + qa.callerBaseline?.let { callerChanges(repoRoot, it) }.orEmpty()

  internal fun callerChanges(
    repoRoot: Path,
    caller: QaCallerBaseline,
  ): List<String> = listOfNotNull(identityChange(repoRoot, caller), statusChange(repoRoot, caller.status))

  internal fun currentTreeIdentity(repoRoot: Path): String? =
    (gitOperations.repositoryTreeIdentity(repoRoot) as? WorkflowGitOperationResult.Ok)
      ?.value
      ?.trim()
      ?.takeIf(String::isNotBlank)

  private fun identityChange(
    repoRoot: Path,
    caller: QaCallerBaseline,
  ): String? =
    try {
      val baseline = gitOperations.worktreeBaseline(repoRoot, ::cannotStart)
      if (baseline.head != caller.head || treeIdentity(repoRoot) != caller.treeIdentity) {
        warnChanged("HEAD or the tested tree changed during QA.")
      } else {
        null
      }
    } catch (failure: SkillBillRuntimeException) {
      failure.rethrowUnless(failure.isQaFailure)
      warnChanged("the runtime could not reread the repository: ${failure.message.orEmpty()}")
    }

  private fun statusChange(
    repoRoot: Path,
    before: String,
  ): String? =
    try {
      val after = gitOperations.worktreeBaseline(repoRoot, ::cannotStart).status
      if (after == before) {
        null
      } else {
        val beforeLines = before.lines().filter(String::isNotBlank).toSet()
        val afterLines = after.lines().filter(String::isNotBlank).toSet()
        val changed = ((afterLines - beforeLines) + (beforeLines - afterLines).map { "was: $it" })
        warnChanged(
          "the worktree status differs from before QA (${changed.take(MAX_CHANGED_LINES).joinToString("; ")}). " +
            "Review and revert any QA edit before committing.",
        )
      }
    } catch (failure: SkillBillRuntimeException) {
      failure.rethrowUnless(failure.isQaFailure)
      warnChanged("the runtime could not reread the worktree status: ${failure.message.orEmpty()}")
    }

  private fun warnChanged(detail: String): String {
    val warning = "QA kept a repository change made while it ran: $detail"
    RuntimeDiagnosticsBestEffortWarning.record(diagnostics, warning)
    return warning
  }

  private fun target(
    repoRoot: Path,
    policy: QaPolicy,
    warnings: MutableList<String>,
    openCheckout: () -> QaCheckoutOpening,
  ): QaExecutionTarget {
    if (policy == QaPolicy.OFF) {
      return QaExecutionTarget.Skipped(
        QaSkipReason.DISABLED_POLICY,
        "QA is disabled by policy.",
      )
    }
    return when (val resolution = capabilities.resolve(repoRoot)) {
      is QaCapabilityResolution.Declared -> checkoutTarget(resolution, openCheckout(), warnings)
      is QaCapabilityResolution.Absent -> QaExecutionTarget.Skipped(QaSkipReason.UNSUPPORTED_TARGET, resolution.detail)
      is QaCapabilityResolution.Incompatible ->
        capabilityUnavailable("The installed QA capability cannot run: ${resolution.reason}", warnings)
    }
  }

  private fun checkoutTarget(
    resolution: QaCapabilityResolution.Declared,
    opening: QaCheckoutOpening,
    warnings: MutableList<String>,
  ): QaExecutionTarget =
    when (opening) {
      is QaCheckoutOpening.Opened -> {
        warnings += opening.warnings
        QaExecutionTarget.Supported(resolution.targets, opening.checkout)
      }
      is QaCheckoutOpening.Unavailable -> {
        warnings += opening.detail
        QaExecutionTarget.Skipped(QaSkipReason.ISOLATION_UNAVAILABLE, opening.detail)
      }
    }

  private fun capabilityUnavailable(
    warning: String,
    warnings: MutableList<String>,
  ): QaExecutionTarget.Skipped {
    RuntimeDiagnosticsBestEffortWarning.record(
      diagnostics,
      "qa skipped: seam=qa_capability value_expected=declared value_used=capability_absent " +
        "reason=${warning.take(MAX_RECORD_REASON)}",
    )
    warnings += warning
    return QaExecutionTarget.Skipped(QaSkipReason.CAPABILITY_ABSENT, warning)
  }

  private fun treeIdentity(repoRoot: Path): String =
    when (val result = gitOperations.repositoryTreeIdentity(repoRoot)) {
      is WorkflowGitOperationResult.Ok -> result.value.trim()
      else -> cannotStart("tree identity", result.error)
    }

  private fun revisionTreeIdentity(
    repoRoot: Path,
    revision: String,
  ): String =
    when (val result = gitOperations.revisionTreeIdentity(repoRoot, revision)) {
      is WorkflowGitOperationResult.Ok -> result.value.trim()
      else -> cannotStart("tree identity of $revision", result.error)
    }

  private fun releaseLeases(
    repoRoot: Path,
    invocationId: String,
  ): List<QaRetainedResource> =
    leases.releaseAllFor(repoRoot, invocationId).filterIsInstance<QaLeaseReleaseOutcome.Failed>().map { failed ->
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "qa device lease retained: seam=qa_cleanup value_expected=released value_used=retained " +
          "device_fingerprint=${failed.retainedIdentity} reason=${failed.detail.take(MAX_RECORD_REASON)}",
      )
      QaRetainedResource("device_lease", failed.retainedIdentity)
    }

  private fun closeWorkspace(workspace: QaWorkspace): List<QaRetainedResource> =
    try {
      workspaces.close(workspace)
      emptyList()
    } catch (failure: SkillBillRuntimeException) {
      failure.rethrowUnless(failure.isQaDeviceLeaseFailure)
      RuntimeDiagnosticsBestEffortWarning.record(
        diagnostics,
        "qa invocation owner retained: seam=qa_cleanup value_expected=removed value_used=retained " +
          "invocation=${workspace.invocationId} reason=${failure.qaDeviceLeaseReason.take(MAX_RECORD_REASON)}",
      )
      listOf(QaRetainedResource("invocation_owner", workspace.invocationId))
    }

  private fun cannotStart(
    anchor: String,
    error: String,
  ): Nothing = throw qaRepositoryUnreadable("could not read $anchor: $error")

  private companion object {
    const val MAX_RECORD_REASON = 200
    const val MAX_CHANGED_LINES = 10
  }
}

internal data class QaExecutionResult<T>(
  val value: T,
  val retainedResources: List<QaRetainedResource>,
  val expected: QaExpectedIdentity,
  val warnings: List<String> = emptyList(),
)
