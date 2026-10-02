package skillbill.engine.goalrunner.reset

import skillbill.application.workflow.decomposition.findDecomposedParentWorkflow
import skillbill.application.workflow.decomposition.requireRuntimeModeForEngineWrite
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.issuekey.normalizeRequiredIssueKey
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionAdmission
import skillbill.engine.goalrunner.manifest.GoalParentProjectionWriter
import skillbill.engine.goalrunner.manifest.mergeConcurrentGoalProgress
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.persist.WorkflowGoalRunnerBlockWrites
import skillbill.engine.goalrunner.planning.hydration.GoalChildPlanningHydratorPort
import skillbill.error.featuretask.FeatureTaskRuntimeExecutionPlanConflictError
import skillbill.error.featuretask.MissingFeatureTaskRuntimeExecutionPlanError
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.goalrunner.GoalRunnerQualityGateSelectionResolver
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskExecutionIdentityPolicy
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact
import java.nio.file.Path
import java.time.Clock

internal data class SavedGoalChildWorkflow(
  internal val state: GoalRunnerManifestState,
  internal val projectionArtifacts: DurableWorkflowArtifacts,
)

internal class WorkflowGoalRunnerChildWorkflowPersistence(
  private val engine: WorkflowEngine,
  private val planningHydrator: GoalChildPlanningHydratorPort,
  private val parentProjection: GoalParentProjectionWriter,
  private val executionAdmission: FeatureTaskRuntimeExecutionAdmission,
  private val clock: Clock,
) {
  private val blockWrites = WorkflowGoalRunnerBlockWrites(engine, clock)

  fun saveInTransaction(
    unitOfWork: UnitOfWork,
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
  ): SavedGoalChildWorkflow {
    requireConsistentChildSetup(state, setup)
    val expectedIdentity = expectedChildIdentity(setup)
    val existingChild = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, setup.workflowId)
    if (existingChild == null && setup.executionPlan == null) {
      missingPlan()
    }
    if (existingChild != null) {
      if (setup.executionPlan == null) throw FeatureTaskRuntimeExecutionPlanConflictError()
      executionAdmission.requireCompatibleDescriptor(unitOfWork.workflowStates, setup.workflowId, setup.executionPlan)
      val persistedIdentity = unitOfWork.workflowStates.getFeatureTaskExecutionIdentity(setup.workflowId)
      if (persistedIdentity != expectedIdentity) {
        throw IncompatibleGoalPlanningPreparationRecoveryError(
          state.parentWorkflowId,
          setup.subtaskId,
          "existing child execution identity conflicts with goal-child setup",
        )
      }
      requireMatchingGoalContinuation(existingChild, state, setup)
      planningHydrator.requireMatchingImport(unitOfWork, existingChild, setup)
      setup.operatorResumePhaseId?.let { phaseId ->
        check(
          blockWrites.reopenBlockedPhaseForOperatorResume(
            unitOfWork,
            phaseId,
            requireNotNull(setup.operatorResumeReason),
            expectedIdentity,
            requireNotNull(setup.executionPlan),
          ),
        ) { "Goal child '${setup.workflowId}' could not be reopened for operator resume." }
      }
    }
    val parentUpdated = updateParentForChildWorkflow(unitOfWork, state)
    val childUpdated =
      if (existingChild == null) {
        openGoalChildWorkflow(unitOfWork, state, setup, parentUpdated.workflowId)
      } else {
        existingChild
      }
    if (existingChild == null) {
      unitOfWork.workflowStates.saveRecord(
        WorkflowFamily.TASK_RUNTIME,
        childUpdated.toRecord().copy(issueKey = normalizeRequiredIssueKey(state.manifest.issueKey)),
      )
      val identity = expectedIdentity
      FeatureTaskExecutionIdentityPolicy.validate(identity)
      unitOfWork.workflowStates.saveFeatureTaskExecutionIdentity(identity)
    }
    val refreshedParent =
      unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentUpdated.workflowId) ?: parentUpdated
    return SavedGoalChildWorkflow(
      state =
        GoalRunnerManifestState(
          parentWorkflowId = refreshedParent.workflowId,
          dbPath = unitOfWork.dbPath.toString(),
          manifest = refreshedParent.decompositionRuntime() ?: state.manifest,
          controlState = unitOfWork.goalRunnerControls.controlState(refreshedParent.workflowId),
        ),
      projectionArtifacts = refreshedParent.artifacts,
    )
  }

  private fun expectedChildIdentity(setup: GoalRunnerChildWorkflowSetup) =
    FeatureTaskExecutionIdentity(
      workflowId = setup.workflowId,
      normalizedIssueKey = setup.normalizedIssueKey,
      repositoryIdentity = setup.repositoryIdentity,
      governedSpecPath = setup.governedSpecPath,
      mode = FeatureTaskWorkflowMode.RUNTIME,
      routeScope = FeatureTaskRouteScope.GOAL_CHILD,
    )

  private fun requireConsistentChildSetup(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
  ) {
    val request = setup.planningHydration ?: return
    val selected = state.manifest.subtasks.singleOrNull { it.id == setup.subtaskId }
    val failures =
      listOfNotNull(
        "parent workflow".takeIf { request.identity.parentGoalWorkflowId != state.parentWorkflowId },
        "issue key".takeIf {
          request.identity.normalizedIssueKey != setup.normalizedIssueKey ||
            setup.normalizedIssueKey != normalizeRequiredIssueKey(state.manifest.issueKey)
        },
        "repository".takeIf { request.identity.repositoryIdentity != setup.repositoryIdentity },
        "subtask".takeIf { request.descriptor.subtaskId != setup.subtaskId },
        "governed spec".takeIf { request.descriptor.governedSubSpecPath != setup.governedSpecPath },
        "manifest subtask".takeIf {
          selected == null ||
            canonicalGovernedSpecPath(selected.specPath, setup.repositoryIdentity) != setup.governedSpecPath
        },
      )
    if (failures.isNotEmpty()) {
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        state.parentWorkflowId,
        setup.subtaskId,
        "hydration ${failures.joinToString()} does not match child setup",
      )
    }
  }

  private fun canonicalGovernedSpecPath(
    specPath: String,
    repositoryIdentity: String,
  ): String {
    val repository =
      Path.of(repositoryIdentity.removePrefix(FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX))
    val lexical =
      Path.of(specPath).let { if (it.isAbsolute) it else repository.resolve(it) }
        .toAbsolutePath().normalize()
    val resolved = runCatching { lexical.toRealPath() }.getOrElse { lexical }
    return runCatching { repository.relativize(resolved).joinToString("/") }.getOrElse { specPath }
  }

  private fun requireMatchingGoalContinuation(
    existing: WorkflowStateSnapshot,
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
  ) {
    val continuation = DurableWorkflowArtifacts.fromMap(existing.artifacts).goalContinuationArtifact()
    val matches =
      continuation?.issueKey == state.manifest.issueKey &&
        continuation.subtaskId == setup.subtaskId &&
        continuation.parentWorkflowId == state.parentWorkflowId &&
        continuation.goalBranch == setup.goalBranch && continuation.suppressPr
    if (!matches) {
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        state.parentWorkflowId,
        setup.subtaskId,
        "existing child goal continuation conflicts with child setup",
      )
    }
  }

  private fun updateParentForChildWorkflow(
    unitOfWork: UnitOfWork,
    state: GoalRunnerManifestState,
  ): WorkflowStateSnapshot {
    val existingRecord =
      unitOfWork.workflowStates.getFeatureTaskWorkflow(state.parentWorkflowId)
        ?: unitOfWork.workflowStates.findDecomposedParentWorkflow(
          state.manifest.issueKey,
        )
        ?: error("Unknown decomposed parent workflow '${state.parentWorkflowId}'.")
    existingRecord.requireRuntimeModeForEngineWrite()
    val existingParent = existingRecord.toSnapshot()
    val parentUpdated =
      engine.updateRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        existingParent,
        WorkflowUpdateInput(
          workflowStatus = existingParent.workflowStatus,
          currentStepId = existingParent.currentStepId,
          stepUpdates = null,
          artifactsPatch =
            parentProjection.artifacts(
              mergeConcurrentGoalProgress(
                existingParent.decompositionRuntime() ?: state.manifest,
                state.manifest,
              ),
              existingParent.artifacts,
            ),
          sessionId = existingParent.sessionId.orEmpty(),
          replaceArtifacts = true,
        ),
      )
    unitOfWork.workflowStates.saveRecord(
      WorkflowFamily.TASK_RUNTIME,
      parentUpdated.toRecord().copy(issueKey = normalizeRequiredIssueKey(state.manifest.issueKey)),
    )
    return parentUpdated
  }

  private fun openGoalChildWorkflow(
    unitOfWork: UnitOfWork,
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
    parentWorkflowId: String,
  ): WorkflowStateSnapshot {
    val openedChild =
      engine.openRecord(
        WorkflowFamily.TASK_RUNTIME.definition,
        setup.workflowId,
        "${WorkflowFamily.TASK_RUNTIME.definition.defaultSessionPrefix}-${state.manifest.issueKey}",
        WorkflowFamily.TASK_RUNTIME.definition.defaultInitialStepId,
      )
    val hydration =
      planningHydrator.hydrate(
        unitOfWork,
        setup,
        requireNotNull(setup.planningHydration) {
          "Prepared goal child '${setup.subtaskId}' requires planning hydration."
        },
      )
    return engine.updateRecord(
      WorkflowFamily.TASK_RUNTIME.definition,
      openedChild,
      WorkflowUpdateInput(
        workflowStatus = openedChild.workflowStatus,
        currentStepId = hydration.currentStepId,
        stepUpdates = hydration.stepUpdates,
        artifactsPatch =
          WorkflowArtifactPatch.from(
            LinkedHashMap(childWorkflowArtifacts(state, setup, parentWorkflowId)).apply {
              putAll(hydration.artifacts)
            },
          ),
        sessionId = openedChild.sessionId.orEmpty(),
      ),
    )
  }

  private fun childWorkflowArtifacts(
    state: GoalRunnerManifestState,
    setup: GoalRunnerChildWorkflowSetup,
    parentWorkflowId: String,
  ): Map<String, Any?> =
    linkedMapOf<String, Any?>().apply {
      setup.executionPlan?.let { descriptor ->
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.putInto(
          this,
          JsonCodec.parseValue(descriptor.encoded().toString(Charsets.UTF_8)),
        )
      }
      putAll(
        FeatureTaskRuntimeGoalContinuationArtifact(
          issueKey = state.manifest.issueKey,
          subtaskId = setup.subtaskId,
          suppressPr = true,
          goalBranch = setup.goalBranch,
          parentWorkflowId = parentWorkflowId,
          codeReviewMode = setup.reviewPolicy.codeReviewMode,
          validationDepth = ValidationDepth.FULL,
          qualityGateSelection = GoalRunnerQualityGateSelectionResolver.resolve(state.manifest, setup.subtaskId),
          subtaskName =
            state.manifest.subtasks.firstOrNull { it.id == setup.subtaskId }?.name?.takeIf(
              String::isNotBlank,
            ),
        ).toWorkflowArtifactPatch(),
      )
      putAll(
        mapOf(
          DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.entry(
            GoalSubtaskReviewState.initial(
              reviewBaseSha = setup.reviewBaseline.reviewBaseSha,
              baselineUntrackedPaths = setup.reviewBaseline.baselineUntrackedPaths,
              codeReviewMode = setup.reviewPolicy.codeReviewMode,
            ).toPersistenceWire(),
          ),
        ),
      )
      put(
        "install_sync_result",
        mapOf(
          SharedPayloadKeys.STATUS to "deferred",
          "reason" to
            "goal-continuation defers installer, uninstall, and install-sync flows until the parent goal exits; " +
            "deferred install sync must not block subtask completion",
        ),
      )
    }

  private fun missingPlan(): Nothing = throw MissingFeatureTaskRuntimeExecutionPlanError()
}
