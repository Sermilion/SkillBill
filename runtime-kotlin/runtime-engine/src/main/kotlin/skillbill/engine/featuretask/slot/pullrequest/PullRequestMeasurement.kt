package skillbill.engine.featuretask.slot.pullrequest

import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeMeasuredFactKeys
import skillbill.engine.diagnostics.RuntimeDiagnosticsBestEffortWarning
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import java.nio.file.Path

internal class PullRequestMeasurement(
  private val lookup: PullRequestIdentityLookup,
  private val repoRoot: Path,
  private val diagnostics: RuntimeDiagnostics,
) {
  fun identity(branch: String?): PullRequestIdentity =
    if (branch.isNullOrBlank()) {
      PullRequestIdentity.Unavailable("the workflow has no resolved branch")
    } else {
      runCatching { lookup.lookup(repoRoot, branch) }
        .getOrElse { error -> PullRequestIdentity.Unavailable("pull request lookup failed: ${error.message}") }
    }

  fun facts(
    before: PullRequestIdentity?,
    after: PullRequestIdentity,
  ): Map<String, Any> =
    when (after) {
      is PullRequestIdentity.Found ->
        mapOf(
          FeatureTaskRuntimeMeasuredFactKeys.PR_URL to after.url,
          FeatureTaskRuntimeMeasuredFactKeys.PR_NUMBER to after.number,
          FeatureTaskRuntimeMeasuredFactKeys.PR_CREATED to created(before, after),
        )
      PullRequestIdentity.Absent -> unknown("pr found no open pull request for the branch after the step")
      is PullRequestIdentity.Merged -> unknown("the pull request was merged before its creation could be measured")
      is PullRequestIdentity.Unavailable -> unknown("pr could not look the pull request up: ${after.reason}")
    }

  fun created(
    before: PullRequestIdentity?,
    after: PullRequestIdentity.Found,
  ): Any =
    when (before) {
      PullRequestIdentity.Absent -> true
      is PullRequestIdentity.Found -> before.number != after.number
      is PullRequestIdentity.Merged -> before.number != after.number
      is PullRequestIdentity.Unavailable -> {
        record("pr could not look the pull request up before the step: ${before.reason}")
        FeatureTaskRuntimeMeasuredFactKeys.UNKNOWN
      }
      null -> {
        record("pr ran without a pre-step pull request lookup")
        FeatureTaskRuntimeMeasuredFactKeys.UNKNOWN
      }
    }

  private fun unknown(reason: String): Map<String, Any> {
    record(reason)
    return MEASURED_KEYS.associateWith { FeatureTaskRuntimeMeasuredFactKeys.UNKNOWN }
  }

  private fun record(reason: String) = RuntimeDiagnosticsBestEffortWarning.record(diagnostics, reason)

  private companion object {
    val MEASURED_KEYS =
      listOf(
        FeatureTaskRuntimeMeasuredFactKeys.PR_URL,
        FeatureTaskRuntimeMeasuredFactKeys.PR_NUMBER,
        FeatureTaskRuntimeMeasuredFactKeys.PR_CREATED,
      )
  }
}
