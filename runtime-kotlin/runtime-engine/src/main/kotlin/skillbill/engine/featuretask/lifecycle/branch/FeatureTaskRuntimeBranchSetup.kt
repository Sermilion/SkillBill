package skillbill.engine.featuretask.lifecycle.branch

import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.defaultFeatureBranch
import skillbill.contracts.issuekey.issueAndFeature
import skillbill.workflow.gitops.ProtectedBranches
import java.nio.file.Path

object FeatureTaskRuntimeBranchSetup {
  private const val DEFAULT_BASE_BRANCH: String = "main"

  internal fun targetBranch(
    issueKey: String,
    specReference: String,
  ): FeatureTaskRuntimeTargetBranch {
    val parentName = Path.of(specReference).parent?.fileName?.toString().orEmpty()
    if (parentName.isBlank()) {
      return FeatureTaskRuntimeTargetBranch.invalid(
        "FeatureTaskRuntimeBranchSetup cannot derive a feature branch: spec reference " +
          "'$specReference' has no parent directory to parse 'feat/{ISSUE_KEY}-{feature-name}' from.",
      )
    }
    val (parsedIssueKey, _) = issueAndFeature(parentName)
    return if (issueKey != parsedIssueKey) {
      FeatureTaskRuntimeTargetBranch.invalid(
        "FeatureTaskRuntimeBranchSetup issue-key mismatch: request issue key '$issueKey' does not " +
          "match the issue key '$parsedIssueKey' parsed from spec parent directory '$parentName'; " +
          "refusing to create a divergent feature branch.",
      )
    } else {
      FeatureTaskRuntimeTargetBranch.resolved(defaultFeatureBranch(Path.of(specReference).parent.resolve("spec.md")))
    }
  }

  internal fun decide(
    issueKey: String,
    specReference: String,
    currentBranch: String,
  ): FeatureTaskRuntimeBranchDecision {
    val normalizedCurrent = currentBranch.trim()
    val mustCreate = normalizedCurrent.isBlank() || protectedBranchName(normalizedCurrent) != null
    if (!mustCreate) {
      return FeatureTaskRuntimeBranchDecision.resolved(branch = normalizedCurrent, baseBranch = null, create = false)
    }
    val target = targetBranch(issueKey, specReference)
    return target.resolvedBranch?.let { resolvedBranch ->
      FeatureTaskRuntimeBranchDecision.resolved(
        branch = resolvedBranch,
        baseBranch = DEFAULT_BASE_BRANCH,
        create = true,
      )
    } ?: FeatureTaskRuntimeBranchDecision.invalid(requireNotNull(target.invalidReason))
  }

  internal fun goalContinuationDecision(goalBranch: String): FeatureTaskRuntimeBranchDecision {
    val normalized = goalBranch.trim()
    val protected = protectedBranchName(normalized)
    return when {
      normalized.isBlank() ->
        FeatureTaskRuntimeBranchDecision.invalid(
          "Goal-continuation branch is blank; refusing to run file-mutating phases.",
        )
      protected != null ->
        FeatureTaskRuntimeBranchDecision.invalid(
          "Goal-continuation branch '$protected' is protected; refusing to run file-mutating phases.",
        )
      else -> FeatureTaskRuntimeBranchDecision.resolved(branch = normalized, baseBranch = null, create = false)
    }
  }
}

internal sealed interface FeatureTaskRuntimeTargetBranch {
  val resolvedBranch: String?
  val invalidReason: String?

  companion object {
    fun resolved(branch: String): FeatureTaskRuntimeTargetBranch = FeatureTaskRuntimeTargetBranchResolved(branch)

    fun invalid(reason: String): FeatureTaskRuntimeTargetBranch = FeatureTaskRuntimeTargetBranchInvalid(reason)
  }
}

internal data class FeatureTaskRuntimeTargetBranchResolved(val branch: String) : FeatureTaskRuntimeTargetBranch {
  override val resolvedBranch: String get() = branch
  override val invalidReason: String? get() = null
}

internal data class FeatureTaskRuntimeTargetBranchInvalid(val reason: String) : FeatureTaskRuntimeTargetBranch {
  override val resolvedBranch: String? get() = null
  override val invalidReason: String get() = reason
}

internal sealed interface FeatureTaskRuntimeBranchDecision {
  val invalidReason: String?

  companion object {
    fun resolved(
      branch: String,
      baseBranch: String?,
      create: Boolean,
    ): FeatureTaskRuntimeBranchDecision = FeatureTaskRuntimeBranchDecisionResolved(branch, baseBranch, create)

    fun invalid(reason: String): FeatureTaskRuntimeBranchDecision = FeatureTaskRuntimeBranchDecisionInvalid(reason)
  }
}

internal data class FeatureTaskRuntimeBranchDecisionResolved(
  val branch: String,
  val baseBranch: String?,
  val create: Boolean,
) : FeatureTaskRuntimeBranchDecision {
  override val invalidReason: String? get() = null
}

internal data class FeatureTaskRuntimeBranchDecisionInvalid(val reason: String) : FeatureTaskRuntimeBranchDecision {
  override val invalidReason: String get() = reason
}

private val PROTECTED_GOAL_BRANCHES: Set<String> = ProtectedBranches.names

fun protectedBranchName(branch: String?): String? =
  branch
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.takeIf { normalized -> normalized.lowercase() in PROTECTED_GOAL_BRANCHES }
