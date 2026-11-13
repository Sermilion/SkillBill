package skillbill.engine.featuretask.lifecycle.execution

import me.tatarka.inject.annotations.Inject
import skillbill.application.workflow.decomposition.isPlanWorkflow
import skillbill.application.workflow.model.FeatureTaskGovernedSpecPathResult
import skillbill.application.workflow.resolveFeatureTaskGovernedSpecPath
import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.execution.AdmittedFeatureTaskRuntimeExecution
import skillbill.engine.goal.goalRepositoryIdentityCandidates
import skillbill.engine.migration.RuntimeMigrationReceipt
import skillbill.error.core.catchingNonCancellation
import skillbill.error.featuretask.IncompatibleFeatureTaskRuntimeExecutionPlanError
import skillbill.error.shellcontent.invalidFeatureTaskExecutionIdentitySchema
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.projectstore.ProjectStorePort
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
public class FeatureTaskRuntimeExecutionEntry(
  private val database: DatabaseSessionFactory,
  private val admission: FeatureTaskRuntimeExecutionAdmission,
  private val resolver: FeatureTaskRuntimeExecutionPlanResolver,
  private val repositories: RepositoryEnclosingRootPort,
  private val identities: FeatureTaskExecutionIdentityResolver,
) {
  internal fun admit(request: FeatureTaskRuntimeRunRequest): AdmittedFeatureTaskRuntimeExecution {
    val root = repositories.canonicalPath(request.repoRoot)
    val expected =
      identities.expected(
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
    var routingMigrationPrepared = false
    var committed = false
    return try {
      val result =
        catchingNonCancellation {
          database.transaction { unit ->
            val accepted =
              admission.admit(
                unit,
                request.workflowId,
                inputs,
                ExecutionAdmissionSelection(
                  expected,
                  invokedAgentId =
                    request.invokedAgentId.takeUnless { request.admittedExecution?.workerOwnership != null },
                ),
              )
            receipt = accepted.migrationReceipt
            routingMigrationPrepared = accepted.routingMigrationPrepared
            requireMatchingRequest(request, accepted)
            accepted
          }
        }.getOrThrow()
      committed = true
      admission.recordTransactionOutcome(
        result.migrationReceipt,
        committed = true,
        workflowId = request.workflowId,
        routingMigrationPrepared = result.routingMigrationPrepared,
      )
      result
    } finally {
      if (!committed) {
        receipt?.let {
          admission.recordTransactionOutcome(
            it,
            committed = false,
            workflowId = request.workflowId,
            routingMigrationPrepared = routingMigrationPrepared,
          )
        }
      }
    }
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

public fun RepositoryEnclosingRootPort.governedFeatureTaskSpecPath(
  projectStore: ProjectStorePort,
  workflowId: String,
  repoRoot: Path,
  specPath: Path,
): String =
  when (val result = resolveFeatureTaskGovernedSpecPath(this, projectStore, repoRoot, specPath)) {
    is FeatureTaskGovernedSpecPathResult.Ok -> result.relativePath
    is FeatureTaskGovernedSpecPathResult.OutsideRepository ->
      throw invalidFeatureTaskExecutionIdentitySchema(
        workflowId,
        "spec is outside the project store specs root ${projectStore.resolve(repoRoot).specsRoot} " +
          "(`sona project path` prints the project store) and outside a " +
          "${LegacyProjectStatePaths.FEATURE_SPECS_PREFIX} in the checkout",
      )
    FeatureTaskGovernedSpecPathResult.InvalidGovernedPath ->
      throw invalidFeatureTaskExecutionIdentitySchema(
        workflowId,
        "spec is not Markdown beneath the project store specs root ${projectStore.resolve(repoRoot).specsRoot} " +
          "(`sona project path` prints the project store); a ${LegacyProjectStatePaths.FEATURE_SPECS_PREFIX} " +
          "spec is accepted only as a legacy in-place spec",
      )
  }

@Inject
public class FeatureTaskExecutionIdentityResolver(
  private val database: DatabaseSessionFactory,
  private val repositories: RepositoryEnclosingRootPort,
  private val projectStore: ProjectStorePort,
) {
  internal fun openingIdentity(
    repoRoot: Path,
    routeScope: FeatureTaskRouteScope,
    planWorkflow: Boolean = false,
  ): String =
    if (routeScope == FeatureTaskRouteScope.GOAL_CHILD || planWorkflow) {
      repositories.primaryRepositoryIdentity(repoRoot)
    } else {
      repositories.repositoryIdentity(repoRoot)
    }

  internal fun governedSpecPath(
    workflowId: String,
    repoRoot: Path,
    specPath: Path,
  ): String = repositories.governedFeatureTaskSpecPath(projectStore, workflowId, repoRoot, specPath)

  internal fun expected(
    workflowId: String,
    issueKey: String,
    repoRoot: Path,
    specPath: Path,
    routeScope: FeatureTaskRouteScope,
  ): FeatureTaskExecutionIdentity =
    FeatureTaskExecutionIdentity(
      workflowId,
      FeatureTaskExecutionIdentityPolicy.normalizeIssueKey(issueKey, workflowId),
      expectedRepositoryIdentity(workflowId, repoRoot, routeScope),
      governedSpecPath(workflowId, repoRoot, specPath),
      FeatureTaskWorkflowMode.RUNTIME,
      routeScope,
    )

  private fun expectedRepositoryIdentity(
    workflowId: String,
    repoRoot: Path,
    routeScope: FeatureTaskRouteScope,
  ): String {
    val stored =
      database.read { unit ->
        unit.workflowStates.getFeatureTaskExecutionIdentity(workflowId)
          ?.takeIf { identity ->
            identity.routeScope == FeatureTaskRouteScope.GOAL_CHILD ||
              unit.workflowStates.getFeatureTaskWorkflow(workflowId)?.isPlanWorkflow() == true
          }?.repositoryIdentity
      }
    return stored?.takeIf { it in goalRepositoryIdentityCandidates(repoRoot, repositories) }
      ?: openingIdentity(repoRoot, routeScope)
  }
}
