package skillbill.engine.featuretask.slot.execution

import me.tatarka.inject.annotations.Inject
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.slot.execution.model.AdmittedFeatureTaskRuntimeExecution
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.shellcontent.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.repository.RepositoryEnclosingRootPort
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
    val expected = FeatureTaskExecutionIdentity(
      request.workflowId, request.issueKey.trim().uppercase(), repositories.repositoryIdentity(root),
      root.relativize(canonicalSpec).joinToString("/"), FeatureTaskWorkflowMode.RUNTIME,
      if (request.goalContinuation == null) FeatureTaskRouteScope.STANDALONE else FeatureTaskRouteScope.GOAL_CHILD,
    )
    val inputs = request.admittedExecution?.effectiveInputs ?: resolver.resolveInputs(
      root, request.goalContinuation?.qualityGateSelection,
      request.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT, request.timeout,
    )
    return database.transaction { unit ->
      val accepted = admission.admit(unit.workflowStates, request.workflowId, inputs, expected)
      if ((request.transitionsOverride != null && request.transitionsOverride != accepted.plan.traversal) ||
        request.runInvariants.codeReviewMode.toRuntimeSelection() != accepted.plan.reviewSelection ||
        request.timeout?.inWholeMilliseconds != inputs.phaseTimeoutMillis ||
        (request.goalContinuation?.validationDepth ?: ValidationDepth.DEFAULT) != inputs.validationDepth
      ) throw IncompatibleFeatureTaskRuntimeExecutionPlanError()
      accepted
    }
  }

  private fun skillbill.review.context.model.launch.CodeReviewExecutionMode.toRuntimeSelection() =
    RuntimeReviewSelection.valueOf(name)
}
