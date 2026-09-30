package skillbill.engine.featuretask.slot.pullrequest

import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeReadinessEvidencePort
import skillbill.engine.featuretask.runloop.observability.emitFeatureTaskRuntimeEventSafely
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeReadinessEvidenceSchemaError
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.workflow.gitops.model.WorkflowReadinessTreeIdentityResult
import skillbill.ports.workflow.gitops.readiness.ReadinessTreeIdentityGitOperations
import java.nio.file.Path

class PullRequestReadinessGate(
  private val readinessEvidence: FeatureTaskRuntimeReadinessEvidencePort,
  private val diagnostics: RuntimeDiagnostics,
) {
  internal fun blockedReason(
    workflowId: String,
    repoRoot: Path,
    baseBranch: String,
    gitOperations: ReadinessTreeIdentityGitOperations,
  ): String? {
    val identity =
      (
        gitOperations.resolveReadinessTreeIdentity(repoRoot, baseBranch, workflowId)
          as? WorkflowReadinessTreeIdentityResult.Resolved
      )?.identity
        ?: return blocked("readiness-pr-identity", "Readiness identity is unavailable for PR entry.")
    val persisted =
      runCatching { readinessEvidence.loadReadinessEvidence(workflowId) }.getOrElse { error ->
        recordDegradation("readiness-pr-persistence", "Could not load readiness evidence: ${error.message.orEmpty()}")
        return "Readiness evidence could not be loaded for PR entry."
      } ?: return blocked("readiness-pr-persistence", "Readiness evidence is missing for PR entry.")
    return try {
      persisted.requireReady("pr", identity.sourceTreeSha, identity.baseRefSha, identity.headSha)
      null
    } catch (error: InvalidFeatureTaskRuntimeReadinessEvidenceSchemaError) {
      blocked("readiness-pr-identity", error.message.orEmpty())
    }
  }

  private fun blocked(
    seam: String,
    reason: String,
  ): String {
    recordDegradation(seam, reason)
    return reason
  }

  private fun recordDegradation(
    seam: String,
    reason: String,
  ) {
    emitFeatureTaskRuntimeEventSafely(diagnostics, "readiness-gate-$seam") {
      RuntimeDiagnosticsBestEffortWarning.record(diagnostics, reason)
    }
  }
}
