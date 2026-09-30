package skillbill.engine.featuretask.lifecycle.execution

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.shellcontent.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.repository.RepositoryEnclosingRootPort
import skillbill.review.context.model.launch.CodeReviewExecutionMode
import skillbill.workflow.model.FeatureTaskExecutionIdentity
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
    val spec = Path.of(request.runInvariants.specReference).let { if (it.isAbsolute) it else root.resolve(it) }
    val canonicalSpec = repositories.optionalRealPath(spec) ?: spec.toAbsolutePath().normalize()
    if (!canonicalSpec.startsWith(root)) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(request.workflowId, "spec escapes admitted repository")
    }
    val expected =
      FeatureTaskExecutionIdentity(
        request.workflowId,
        request.issueKey.trim().uppercase(),
        repositories.repositoryIdentity(root),
        root.relativize(canonicalSpec).joinToString("/"),
        FeatureTaskWorkflowMode.RUNTIME,
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
    return database.transaction { unit ->
      val accepted = admission.admit(unit.workflowStates, request.workflowId, inputs, expected)
      requireMatchingRequest(request, accepted)
      accepted
    }
  }

  private fun requireMatchingRequest(
    request: FeatureTaskRuntimeRunRequest,
    accepted: AdmittedFeatureTaskRuntimeExecution,
  ) {
    val matchingTraversal =
      request.transitionsOverride == null || request.transitionsOverride == accepted.plan.traversal
    val selectedDepth = request.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT
    val matchingSettings = request.timeout?.inWholeMilliseconds == accepted.effectiveInputs.phaseTimeoutMillis &&
      selectedDepth == accepted.effectiveInputs.validationDepth
    if (!matchingTraversal || !matchingSettings ||
      request.runInvariants.codeReviewMode.toRuntimeSelection() != accepted.plan.reviewSelection
    ) {
      throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
    }
  }

  private fun CodeReviewExecutionMode.toRuntimeSelection() = RuntimeReviewSelection.valueOf(name)
}
