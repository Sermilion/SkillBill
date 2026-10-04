package skillbill.engine.operation.unittestvalue

import skillbill.engine.operation.core.Operation
import skillbill.engine.operation.core.OperationContext
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationRunResult
import skillbill.engine.operation.core.OperationStepResult
import skillbill.engine.operation.core.unresolvableScope
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowPathContentIdentitiesResult
import java.nio.file.Path

class UnitTestValueCheckOperation(
  private val gitOperations: WorkflowGitOperations,
) : Operation {
  override val id: String = "unit-test-value-check"

  override fun run(context: OperationContext): OperationRunResult {
    val scope = resolveScope(context) { return OperationRunResult.Finished(it) }
    val candidates = scope.paths.filter(UnitTestPathClassifier::isUnitTest).distinct().sorted()
    val tests =
      if (scope.fromGitChanges) {
        existing(context.repoRoot, candidates, scope.label) { return OperationRunResult.Finished(it) }
      } else {
        candidates
      }
    if (tests.isEmpty()) {
      return OperationRunResult.Finished(
        OperationOutcome.Completed("No unit tests in scope (${scope.label}); nothing to review.\n"),
      )
    }
    val directive = UnitTestValueCheckPromptRules.reviewDirective(scope.label, tests)
    val outcome =
      when (val step = context.steps.runReadOnly(context, UnitTestValueCheckPromptRules.REVIEW_STEP, directive)) {
        is OperationStepResult.Failed -> OperationOutcome.Failed(step.reason)
        is OperationStepResult.Refused -> step.refusal
        is OperationStepResult.Settled -> OperationOutcome.Completed(step.value.trimEnd() + "\n")
      }
    return OperationRunResult.Finished(outcome)
  }

  private inline fun resolveScope(
    context: OperationContext,
    refuse: (OperationOutcome.Usage) -> Nothing,
  ): ReviewScope {
    val repoRoot = context.repoRoot
    val requested = context.arguments.scope?.trim()?.takeIf(String::isNotEmpty)
    if (requested == null) {
      val changed = gitOperations.repositoryOwnedPaths(repoRoot).names(CURRENT_CHANGES, refuse)
      return ReviewScope(CURRENT_CHANGES, changed, fromGitChanges = true)
    }
    val commit =
      gitOperations.resolveCommit(repoRoot, requested) as? WorkflowGitOperationResult.Ok
        ?: return ReviewScope("path $requested", listOf(requested), fromGitChanges = false)
    val after = commit.value.orEmpty().trim()
    val before =
      (gitOperations.resolveCommit(repoRoot, "$after^") as? WorkflowGitOperationResult.Ok)?.value?.trim()
        ?: refuse(unresolvableScope(requested, "commit $after has no parent to compare against."))
    val listing = gitOperations.runtimePhaseChangedPathsBetweenCommits(repoRoot, before, after)
    val changed = listing.names(requested, refuse)
    return ReviewScope("commit $requested ($after)", changed, fromGitChanges = true)
  }

  private inline fun existing(
    repoRoot: Path,
    paths: List<String>,
    scope: String,
    refuse: (OperationOutcome.Usage) -> Nothing,
  ): List<String> {
    if (paths.isEmpty()) return paths
    return when (val present = gitOperations.pathContentIdentities(repoRoot, paths)) {
      is WorkflowPathContentIdentitiesResult.Resolved -> paths.filter(present.identities::containsKey)
      is WorkflowPathContentIdentitiesResult.Failed -> refuse(unresolvableScope(scope, present.error))
    }
  }

  private inline fun WorkflowGitNameListResult.names(
    scope: String,
    refuse: (OperationOutcome.Usage) -> Nothing,
  ): List<String> =
    when (this) {
      is WorkflowGitNameListResult.Listed -> names
      is WorkflowGitNameListResult.Failed -> refuse(unresolvableScope(scope, error))
    }

  private data class ReviewScope(
    val label: String,
    val paths: List<String>,
    val fromGitChanges: Boolean,
  )
}

private const val CURRENT_CHANGES = "current staged, unstaged, and untracked changes"
