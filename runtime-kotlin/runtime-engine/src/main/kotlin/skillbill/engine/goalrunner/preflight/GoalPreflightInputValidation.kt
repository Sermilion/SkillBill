package skillbill.engine.goalrunner.preflight

import skillbill.contracts.issuekey.normalizeIssueKey
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.error.shellcontent.invalidDecompositionManifestSchema
import skillbill.error.shellcontent.invalidFeatureTaskExecutionIdentitySchema
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import java.nio.file.Path

object GoalPreflightInputValidation {
  fun requireInvokedAgentId(invokedAgentId: String) {
    if (invokedAgentId.isBlank()) {
      throw invalidFeatureTaskExecutionIdentitySchema(
        "preflight request",
        "invoked_agent_id is required",
      )
    }
  }

  fun requireOptionalIdentity(
    field: String,
    value: String?,
  ) {
    if (value?.isBlank() == true) {
      throw invalidFeatureTaskExecutionIdentitySchema(
        "preflight request",
        "$field must be omitted when blank",
      )
    }
  }

  fun resolveRepositoryRoot(
    repoRoot: Path,
    repositoryEnclosingRootPort: RepositoryEnclosingRootPort,
  ): Path =
    runCatching {
      repositoryEnclosingRootPort.canonicalPath(repoRoot.toAbsolutePath().normalize())
    }.getOrElse {
      throw invalidFeatureTaskExecutionIdentitySchema(
        "preflight request",
        "repository root '$repoRoot' cannot be resolved",
        it,
      )
    }

  fun normalizeIssueKey(issueKey: String): String =
    FeatureTaskExecutionIdentityPolicy.normalizeIssueKey(issueKey, "preflight request")

  fun requireManifestIssueKey(
    manifestIssueKey: String,
    requestedIssueKey: String,
  ) {
    if (manifestIssueKey != requestedIssueKey) {
      throw invalidDecompositionManifestSchema(
        sourceLabel = requestedIssueKey,
        reason = "manifest issue_key '$manifestIssueKey' does not match the requested issue key.",
        code = WorkflowFailureCode.DECOMPOSITION_MANIFEST_ISSUE_KEY_MISMATCH,
      )
    }
  }
}
