package skillbill.application.workflow.persist

import skillbill.application.decomposition.mergedArtifacts
import skillbill.application.telemetry.lifecycle.random
import skillbill.application.workflow.model.PersistOpenedWorkflowArgs
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenArgs
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.application.workflow.service.FEATURE_TASK_FAMILY_KINDS
import skillbill.application.workflow.service.INCOMPLETE_FEATURE_TASK_IDENTITY_ERROR
import skillbill.application.workflow.service.SUFFIX_CHARS
import skillbill.application.workflow.service.WORKFLOW_ID_SUFFIX_LENGTH
import skillbill.application.workflow.service.WorkflowService
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.issuekey.normalizeIssueKey
import skillbill.error.featuretask.MissingFeatureTaskRuntimeExecutionPlanError
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.GoalObservabilityArtifacts
import skillbill.goalrunner.model.GoalObservabilityProgressInput
import skillbill.goalrunner.model.GoalObservabilityWorktreeActivity
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.toRecord
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowContinueDecision
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowSnapshotView
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneOffset
import kotlin.random.Random

internal data class WorkflowPersistenceContext(
  val dbPath: String,
  val repositoryCheckpointIdentity: () -> String = { "" },
)

internal data class ProjectionLaunchRequest(
  val stepId: String,
  val producerIteration: Int,
  val repositoryCheckpointIdentity: () -> String = { "" },
)

internal fun incompleteFeatureTaskIdentityError(args: WorkflowServiceOpenArgs): WorkflowOpenResult.Error? {
  val hasIdentityCoordinates = args.repositoryIdentity != null || args.governedSpecPath != null
  val hasIncompleteIdentity =
    hasIncompleteFeatureTaskIdentity(
      args.kind,
      hasIdentityCoordinates,
      args.issueKey,
      args.repositoryIdentity,
      args.governedSpecPath,
    )
  return if (hasIncompleteIdentity) {
    WorkflowOpenResult.Error(
      workflowId = "unassigned",
      error = INCOMPLETE_FEATURE_TASK_IDENTITY_ERROR,
    )
  } else {
    null
  }
}

internal fun persistOpenedWorkflow(args: PersistOpenedWorkflowArgs): WorkflowOpenResult =
  args.database.transaction { unitOfWork ->
    val engine = args.engine
    val family = args.family
    val workflowId = args.workflowId
    val stepId = args.stepId
    if (family == WorkflowFamily.TASK_RUNTIME && args.executionIdentity != null && args.executionPlan == null) {
      throw MissingFeatureTaskRuntimeExecutionPlanError()
    }
    val record =
      engine.openRecord(
        family.definition,
        workflowId,
        args.effectiveSessionId,
        stepId,
      )
    val withExecutionPlan =
      args.executionPlan?.let { descriptor ->
        record.copy(
          artifacts =
            DurableWorkflowArtifacts.fromMap(
              record.artifacts +
                DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.entry(
                  JsonCodec.parseValue(descriptor.encoded().toString(Charsets.UTF_8)),
                ),
            ),
        )
      } ?: record
    args.workflowSnapshotValidator.validate(withExecutionPlan, family.definition.workflowName)
    unitOfWork.workflowStates.saveRecord(
      family,
      withExecutionPlan.toRecord().copy(
        startedAt = null,
        issueKey = normalizeIssueKey(args.issueKey),
      ),
    )
    args.executionIdentity?.let(unitOfWork.workflowStates::saveFeatureTaskExecutionIdentity)
    val saved = unitOfWork.workflowStates.get(family, workflowId) ?: withExecutionPlan
    val currentStep =
      engine.snapshotView(family.definition, saved).steps
        .firstOrNull { it.stepId == stepId }
    val launchProjection =
      launchProjectionIfReady(
        engine,
        family.definition,
        engine.snapshotView(family.definition, saved),
        ProjectionLaunchRequest(
          stepId = stepId,
          producerIteration = currentStep?.attemptCount ?: 0,
          repositoryCheckpointIdentity = args.repositoryCheckpointIdentity,
        ),
      )
    WorkflowOpenResult.Ok(
      workflowId = saved.workflowId,
      dbPath = unitOfWork.dbPath.toString(),
      snapshot = engine.snapshotView(family.definition, saved),
      launchProjection = launchProjection,
    )
  }

internal fun resolveEffectiveSessionId(
  kind: WorkflowFamilyKind,
  sessionId: String,
  definition: WorkflowDefinition,
  workflowId: String,
): String =
  sessionId.ifBlank {
    if (kind == WorkflowFamilyKind.TASK_RUNTIME) "${definition.defaultSessionPrefix}-$workflowId" else ""
  }

internal fun WorkflowUpdateRequest.toWorkflowUpdateInput(): WorkflowUpdateInput =
  WorkflowUpdateInput(
    workflowStatus =
      WorkflowStatus.fromWire(workflowStatus)
        ?: throw InvalidWorkflowStateSchemaError(
          "Invalid workflow_status '$workflowStatus'.",
        ),
    currentStepId = currentStepId,
    stepUpdates = stepUpdates,
    artifactsPatch = artifactsPatch,
    sessionId = sessionId,
  )

internal fun WorkflowContinueDecision.toReopenInput(sessionId: String): WorkflowUpdateInput =
  WorkflowUpdateInput(
    workflowStatus = WorkflowStatus.RUNNING,
    currentStepId = resumeStepId,
    stepUpdates =
      WorkflowStepUpdates.from(
        listOf(
          mapOf(
            SharedPayloadKeys.STEP_ID to resumeStepId,
            SharedPayloadKeys.STATUS to WorkflowStepStatus.RUNNING.wireValue,
            "attempt_count" to nextAttemptCount,
          ),
        ),
      ),
    artifactsPatch = null,
    sessionId = sessionId,
  )

internal fun WorkflowUpdateInput.withGoalObservabilityArtifacts(
  existing: WorkflowStateSnapshot,
  workflowId: String,
  validator: FeatureTaskRuntimeWireArtifactValidator,
  gitOperations: WorkflowGitOperations,
  repoRoot: Path,
): WorkflowUpdateInput {
  val patch = artifactsPatch
  return if (patch?.containsKey("progress_event") != true) {
    this
  } else {
    val existingArtifacts = existing.artifacts
    val mergedArtifacts = LinkedHashMap(existingArtifacts).apply { putAll(patch) }
    val observabilityPatch =
      GoalObservabilityArtifacts.patchForProgressEvent(
        input =
          GoalObservabilityProgressInput(
            artifacts = FeatureTaskRuntimeWorkflowArtifactMap.from(mergedArtifacts),
            workflowId = workflowId,
            workflowStatus = workflowStatus.wireValue,
            currentStepId = currentStepId,
            worktreeActivity =
              gitOperations.worktreeActivity(repoRoot.normalize())
                .takeIf { activity -> activity.status == WorkflowGitOperationStatus.OK }
                ?.let { activity ->
                  GoalObservabilityWorktreeActivity(
                    changedFileSummary = activity.changedFileSummary,
                    diffStat = activity.diffStat,
                  )
                },
          ),
      )
    observabilityPatch?.let { decoded ->
      validateGoalObservabilityPatch(validator, decoded)
      copy(artifactsPatch = WorkflowArtifactPatch.from(LinkedHashMap(patch).apply { putAll(decoded) }))
    } ?: this
  }
}

private fun validateGoalObservabilityPatch(
  validator: FeatureTaskRuntimeWireArtifactValidator,
  patch: FeatureTaskRuntimeWorkflowArtifactMap,
) {
  val latestEvent = DurableWorkflowArtifactFamily.GOAL_OBSERVABILITY_LATEST_EVENT
  validator.validate(
    FeatureTaskRuntimeWireArtifactKind.GOAL_OBSERVABILITY_EVENT,
    FeatureTaskRuntimeWorkflowArtifactMap.from(latestEvent.value(patch)),
    latestEvent.label(),
  )
  val runHistory = DurableWorkflowArtifactFamily.GOAL_OBSERVABILITY_RUN_HISTORY
  (runHistory.value(patch) as List<*>).forEachIndexed { index, item ->
    validator.validate(
      FeatureTaskRuntimeWireArtifactKind.GOAL_OBSERVABILITY_EVENT,
      FeatureTaskRuntimeWorkflowArtifactMap.from(item),
      "${runHistory.label()}[$index]",
    )
  }
}

internal fun buildUpdateOk(
  engine: WorkflowEngine,
  definition: WorkflowDefinition,
  updated: WorkflowStateSnapshot,
  effectiveInput: WorkflowUpdateInput,
  persistenceContext: WorkflowPersistenceContext,
): WorkflowUpdateResult.Ok {
  val snapshot = engine.snapshotView(definition, updated)
  val currentStep = snapshot.steps.firstOrNull { it.stepId == snapshot.currentStepId }
  return WorkflowUpdateResult.Ok(
    workflowId = updated.workflowId,
    dbPath = persistenceContext.dbPath,
    acknowledgement =
      engine.updateAcknowledgementView(
        snapshot = snapshot,
        input = effectiveInput,
      ),
    launchProjection =
      launchProjectionIfReady(
        engine,
        definition,
        snapshot,
        ProjectionLaunchRequest(
          stepId = snapshot.currentStepId,
          producerIteration = currentStep?.attemptCount ?: 0,
          repositoryCheckpointIdentity = persistenceContext.repositoryCheckpointIdentity,
        ),
      ),
  )
}

internal fun launchProjectionIfReady(
  engine: WorkflowEngine,
  definition: WorkflowDefinition,
  snapshot: WorkflowSnapshotView,
  request: ProjectionLaunchRequest,
) = definition.inputProjectionsByStep[request.stepId]
  ?.takeIf { declaration ->
    declaration.requiredArtifactKeys.all { artifactKey ->
      DurableWorkflowArtifactFamily.RUNTIME_REPOSITORY_EVIDENCE.contains(snapshot.artifacts) ||
        snapshot.artifacts.containsKey(artifactKey)
    }
  }
  ?.let {
    engine.launchProjection(
      definition,
      snapshot,
      request.stepId,
      request.producerIteration,
      request.repositoryCheckpointIdentity(),
    )
  }

fun WorkflowService.openFeatureTask(args: WorkflowServiceOpenFeatureTaskArgs): WorkflowOpenResult {
  require(args.kind in FEATURE_TASK_FAMILY_KINDS) {
    "Only runtime feature-task workflows use execution identity."
  }
  return open(
    WorkflowServiceOpenArgs(
      kind = args.kind,
      sessionId = args.sessionId,
      currentStepId = args.currentStepId,
      issueKey = args.issueKey,
      repositoryIdentity = args.repositoryIdentity,
      governedSpecPath = args.governedSpecPath,
      routeScope = args.routeScope,
      executionPlan = args.executionPlan,
    ),
  )
}

fun generateWorkflowId(
  prefix: String,
  clock: Clock,
  random: Random,
): String {
  val now = clock.instant().atOffset(ZoneOffset.UTC)
  val suffix =
    (1..WORKFLOW_ID_SUFFIX_LENGTH).map { SUFFIX_CHARS[random.nextInt(SUFFIX_CHARS.length)] }
      .joinToString("")
  return "$prefix-${now.year}${now.monthValue.twoDigits()}${now.dayOfMonth.twoDigits()}-" +
    "${now.hour.twoDigits()}${now.minute.twoDigits()}${now.second.twoDigits()}-$suffix"
}

private fun Int.twoDigits(): String = toString().padStart(2, '0')
