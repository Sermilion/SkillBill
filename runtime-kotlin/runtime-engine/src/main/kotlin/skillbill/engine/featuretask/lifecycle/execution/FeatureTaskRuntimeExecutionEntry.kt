package skillbill.engine.featuretask.lifecycle.execution

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.model.FeatureTaskGovernedSpecPathResult
import skillbill.application.workflow.resolveFeatureTaskGovernedSpecPath
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.migration.RuntimeMigrationReceipt
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.shellcontent.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.skeleton.RuntimeReviewSelection
import java.nio.file.Path

@Inject
class FeatureTaskRuntimeExecutionEntry(
  private val database: DatabaseSessionFactory,
  private val admission: FeatureTaskRuntimeExecutionAdmission,
  private val resolver: FeatureTaskRuntimeExecutionPlanResolver,
  private val repositories: RepositoryEnclosingRootPort,
) {
  fun admit(request: FeatureTaskRuntimeRunRequest): AdmittedFeatureTaskRuntimeExecution {
    val root = repositories.canonicalPath(request.repoRoot)
    val expected =
      repositories.expectedFeatureTaskExecutionIdentity(
        request.workflowId,
        request.issueKey,
        request.repoRoot,
        Path.of(request.runInvariants.specReference),
        if (request.goalContinuation == null) FeatureTaskRouteScope.STANDALONE else FeatureTaskRouteScope.GOAL_CHILD,
      )
    val inputs =
      request.admittedExecution?.effectiveInputs ?: resolver.resolveInputs(
        root,
        request.goalContinuation?.qualityGateSelection,
        request.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT,
        request.timeout,
        request.workflowId,
      )
    var receipt: RuntimeMigrationReceipt? = null
    return runCatching {
      database.transaction { unit ->
        val accepted = admission.admit(unit, request.workflowId, inputs, expected)
        receipt = accepted.migrationReceipt
        requireMatchingRequest(request, accepted)
        accepted
      }
    }.onFailure {
      receipt?.let { admission.recordTransactionOutcome(it, committed = false) }
    }.getOrThrow().also { admission.recordTransactionOutcome(it.migrationReceipt, committed = true) }
  }

  private fun requireMatchingRequest(
    request: FeatureTaskRuntimeRunRequest,
    accepted: AdmittedFeatureTaskRuntimeExecution,
  ) {
    val matchingTraversal =
      request.transitionsOverride == null || request.transitionsOverride == accepted.plan.traversal
    val selectedDepth = request.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT
    val matchingSettings =
      request.timeout?.inWholeMilliseconds == accepted.effectiveInputs.phaseTimeoutMillis &&
        selectedDepth == accepted.effectiveInputs.validationDepth
    if (!matchingTraversal || !matchingSettings ||
      request.runInvariants.codeReviewMode.toRuntimeSelection() != accepted.plan.reviewSelection
    ) {
      throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
    }
  }

  private fun CodeReviewExecutionMode.toRuntimeSelection() = RuntimeReviewSelection.valueOf(name)
}

internal fun RepositoryEnclosingRootPort.governedFeatureTaskSpecPath(
  workflowId: String,
  repoRoot: Path,
  specPath: Path,
): String =
  when (val result = resolveFeatureTaskGovernedSpecPath(this, repoRoot, specPath)) {
    is FeatureTaskGovernedSpecPathResult.Ok -> result.relativePath
    is FeatureTaskGovernedSpecPathResult.OutsideRepository ->
      throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "spec escapes admitted repository")
    FeatureTaskGovernedSpecPathResult.InvalidGovernedPath ->
      throw InvalidFeatureTaskExecutionIdentitySchemaError(workflowId, "spec is not Markdown beneath .feature-specs/")
  }

internal fun RepositoryEnclosingRootPort.expectedFeatureTaskExecutionIdentity(
  workflowId: String,
  issueKey: String,
  repoRoot: Path,
  specPath: Path,
  routeScope: FeatureTaskRouteScope,
): FeatureTaskExecutionIdentity =
  FeatureTaskExecutionIdentity(
    workflowId,
    FeatureTaskExecutionIdentityPolicy.normalizeIssueKey(issueKey, workflowId),
    repositoryIdentity(repoRoot),
    governedFeatureTaskSpecPath(workflowId, repoRoot, specPath),
    FeatureTaskWorkflowMode.RUNTIME,
    routeScope,
  )
