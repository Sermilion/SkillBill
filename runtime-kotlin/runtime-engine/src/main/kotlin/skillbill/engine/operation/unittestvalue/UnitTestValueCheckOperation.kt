package skillbill.engine.operation.unittestvalue

import skillbill.engine.operation.core.Operation
import skillbill.engine.operation.core.OperationContext
import skillbill.engine.operation.core.OperationOutcome
import skillbill.engine.operation.core.OperationRunResult
import skillbill.engine.operation.core.OperationStepResult
import skillbill.error.operation.UnresolvableOperationScopeError
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
    val scope = resolveScope(context)
    val candidates = scope.paths.filter(UnitTestPathClassifier::isUnitTest).distinct().sorted()
    val tests = if (scope.fromGitChanges) existing(context.repoRoot, candidates, scope.label) else candidates
    if (tests.isEmpty()) {
      return OperationRunResult.Finished(
        OperationOutcome.Completed("No unit tests in scope (${scope.label}); nothing to review.\n"),
      )
    }
    val directive = UnitTestValueCheckPromptRules.reviewDirective(scope.label, tests)
    val outcome =
      when (val step = context.steps.runReadOnly(context, UnitTestValueCheckPromptRules.REVIEW_STEP, directive)) {
        is OperationStepResult.Failed -> OperationOutcome.Failed(step.reason)
        is OperationStepResult.Settled -> OperationOutcome.Completed(step.value.trimEnd() + "\n")
      }
    return OperationRunResult.Finished(outcome)
  }

  private fun resolveScope(context: OperationContext): ReviewScope {
    val repoRoot = context.repoRoot
    val requested = context.arguments.scope?.trim()?.takeIf(String::isNotEmpty)
    if (requested == null) {
      val changed = gitOperations.repositoryOwnedPaths(repoRoot).names(CURRENT_CHANGES)
      return ReviewScope(CURRENT_CHANGES, changed, fromGitChanges = true)
    }
    val commit =
      gitOperations.resolveCommit(repoRoot, requested) as? WorkflowGitOperationResult.Ok
        ?: return ReviewScope("path $requested", listOf(requested), fromGitChanges = false)
    val after = commit.value.orEmpty().trim()
    val before =
      (gitOperations.resolveCommit(repoRoot, "$after^") as? WorkflowGitOperationResult.Ok)?.value?.trim()
        ?: throw UnresolvableOperationScopeError(requested, "commit $after has no parent to compare against.")
    val changed = gitOperations.runtimePhaseChangedPathsBetweenCommits(repoRoot, before, after).names(requested)
    return ReviewScope("commit $requested ($after)", changed, fromGitChanges = true)
  }

  private fun existing(
    repoRoot: Path,
    paths: List<String>,
    scope: String,
  ): List<String> {
    if (paths.isEmpty()) return paths
    return when (val present = gitOperations.pathContentIdentities(repoRoot, paths)) {
      is WorkflowPathContentIdentitiesResult.Resolved -> paths.filter(present.identities::containsKey)
      is WorkflowPathContentIdentitiesResult.Failed -> throw UnresolvableOperationScopeError(scope, present.error)
    }
  }

  private fun WorkflowGitNameListResult.names(scope: String): List<String> =
    when (this) {
      is WorkflowGitNameListResult.Listed -> names
      is WorkflowGitNameListResult.Failed -> throw UnresolvableOperationScopeError(scope, error)
    }

  private data class ReviewScope(
    val label: String,
    val paths: List<String>,
    val fromGitChanges: Boolean,
  )
}

private const val CURRENT_CHANGES = "current staged, unstaged, and untracked changes"
