package skillbill.application

import skillbill.contracts.JsonCodec
import skillbill.contracts.projectstore.ProjectStoreLayout
import skillbill.contracts.workflow.goal.GOAL_CHILD_HISTORY_DRAFT_CONTRACT_VERSION
import skillbill.goalrunner.model.FeatureTaskRuntimeGoalContinuationOutcome
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_OPERATOR_REQUEST
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.history.GoalChildHistoryDraft
import skillbill.goalrunner.model.history.GoalChildHistoryDraftDisposition
import skillbill.goalrunner.model.history.GoalChildHistoryDraftPayloadKeys
import skillbill.idestatus.model.IdeStatusPolicy
import skillbill.infrastructure.host.CanonicalRepositoryRoot
import skillbill.infrastructure.host.JdkFeatureTaskRuntimeWorkerSupervisor
import skillbill.infrastructure.host.JdkRuntimeDiagnostics
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.ports.UnitOfWork
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdateResult
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.toRecord
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.telemetry.model.GoalFinishedRecord
import skillbill.telemetry.model.GoalIssueFinishedRecord
import skillbill.telemetry.model.GoalStartedRecord
import skillbill.workflow.decomposition.encodeDecompositionManifestWireMap
import skillbill.workflow.decomposition.intentFor
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.runtime.goalParentArtifactProjection
import skillbill.workflow.decomposition.withParentStatus
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

data class DesktopFixtureWriteResult(
  val projectId: String,
  val specsRoot: Path,
  val bundleDirectories: List<String>,
)

class DesktopFixtureDatabaseWriter(
  private val database: DatabaseSessionFactory,
  private val projectStorePort: ProjectStorePort,
  private val clock: Clock,
  private val checkoutRoot: Path,
) {
  fun write(
    completionPlan: ValidatedFeatureTaskRuntimeExecutionPlan,
    completionCommitSha: String,
  ): DesktopFixtureWriteResult {
    val root = CanonicalRepositoryRoot.canonicalPath(checkoutRoot)
    val persistence =
      FixturePersistence(
        now = clock.instant(),
        checkout = root,
        checkoutRepositoryIdentity = CanonicalRepositoryRoot.repositoryIdentity(root),
        goalRepositoryIdentity = CanonicalRepositoryRoot.primaryRepositoryIdentity(root),
        owner =
          JdkFeatureTaskRuntimeWorkerSupervisor(JdkRuntimeDiagnostics(), JdkHostPlatformPort).currentProcess(),
        completionPlan = completionPlan,
        completionCommitSha = completionCommitSha,
      )
    val project = projectStorePort.resolve(root)
    database.transaction { unit ->
      DesktopFixtureCatalog.taskWorkflows.forEach { persistTask(unit, it, persistence) }
      DesktopFixtureCatalog.goals.forEach { persistGoal(unit, it, persistence) }
      DesktopFixtureCatalog.verifies.forEach { persistVerify(unit, it, persistence) }
      DesktopFixtureCatalog.standalone.forEachIndexed { index, case ->
        persistStandalone(unit, case, index, persistence)
      }
    }
    writeBundles(project.specsRoot)
    return DesktopFixtureWriteResult(
      project.projectId,
      project.specsRoot,
      DesktopFixtureCatalog.bundles.map { it.directory },
    )
  }

  private fun persistTask(
    unit: UnitOfWork,
    case: DesktopFixtureWorkflowCase,
    persistence: FixturePersistence,
  ) {
    val id = "fixture-${case.id}"
    val issueKey = "${DesktopFixtureCatalog.ISSUE_PREFIX}-110"
    val snapshot = taskSnapshot(id, case.status, persistence.now, TASK_STEP, mapOf("fixture" to true))
    unit.workflowStates.saveFeatureTaskWorkflow(
      snapshot.toRecord().copy(issueKey = issueKey),
      FeatureTaskWorkflowMode.RUNTIME,
    )
    unit.workflowStates.saveFeatureTaskExecutionIdentity(
      FeatureTaskExecutionIdentity(
        id,
        issueKey,
        persistence.checkoutRepositoryIdentity,
        ".feature-specs/$issueKey/spec.md",
        FeatureTaskWorkflowMode.RUNTIME,
        FeatureTaskRouteScope.STANDALONE,
      ),
    )
  }

  private fun persistGoal(
    unit: UnitOfWork,
    case: DesktopFixtureGoalCase,
    persistence: FixturePersistence,
  ) {
    val parentId = "fixture-${case.id}"
    val childId = "$parentId-child"
    val kind = case.fixtureKind()
    val manifest =
      goalManifest(
        case,
        childId,
        kind.subtaskStatus,
        persistence.completionCommitSha.takeIf { kind.completed },
      )
    val parent =
      taskSnapshot(
        parentId,
        kind.workflowStatus,
        persistence.now,
        TASK_STEP,
        parentArtifacts(manifest, parentId, childId, kind.completed),
      )
    val childStatus = if (kind.completed) WorkflowStatus.COMPLETED else kind.workflowStatus
    val childStep = if (kind.completed) COMPLETION_STEP else TASK_STEP
    val child =
      taskSnapshot(
        childId,
        childStatus,
        persistence.now,
        childStep,
        childArtifacts(case, parentId, childId, persistence, kind.completed),
      )
    unit.workflowStates.saveFeatureTaskWorkflow(
      parent.toRecord().copy(issueKey = case.issueKey),
      FeatureTaskWorkflowMode.RUNTIME,
    )
    unit.workflowStates.saveFeatureTaskWorkflow(
      child.toRecord().copy(issueKey = case.issueKey),
      FeatureTaskWorkflowMode.RUNTIME,
    )
    unit.workflowStates.saveFeatureTaskExecutionIdentity(
      FeatureTaskExecutionIdentity(
        childId,
        case.issueKey,
        persistence.goalRepositoryIdentity,
        specReference(case.bundleDirectory, "spec_subtask_1.md"),
        FeatureTaskWorkflowMode.RUNTIME,
        FeatureTaskRouteScope.GOAL_CHILD,
      ),
    )
    persistGoalControl(unit, case, parentId, persistence, kind)
    persistGoalTelemetry(unit, case, parentId, persistence, kind)
  }

  private fun persistGoalControl(
    unit: UnitOfWork,
    case: DesktopFixtureGoalCase,
    parentId: String,
    persistence: FixturePersistence,
    kind: GoalFixtureKind,
  ) {
    val now = persistence.now
    val lease =
      if (case.liveLease) {
        GoalRunnerExecutionLease(
          1,
          persistence.owner.processBirthToken,
          persistence.owner.hostIdentity,
          persistence.owner.bootIdentity,
          persistence.owner.pid,
          persistence.owner.processBirthToken,
          now,
          now.plus(24, ChronoUnit.HOURS),
        )
      } else {
        null
      }
    unit.goalRunnerControls.persistControlState(
      parentId,
      GoalRunnerControlState(
        pauseRequested = case.pauseRequested,
        paused = case.paused,
        pauseReason = if (case.paused) GOAL_PAUSE_REASON_OPERATOR_REQUEST else null,
        pausedAt = if (case.paused) now.toString() else null,
        repositoryIdentity = persistence.goalRepositoryIdentity,
        executionLease = lease,
        goalCompletedAt = now.toString().takeIf { kind.completed },
        goalBlockedReason = "Desktop fixture ${case.id}".takeIf { kind.stopped },
      ),
    )
  }

  private fun persistGoalTelemetry(
    unit: UnitOfWork,
    case: DesktopFixtureGoalCase,
    parentId: String,
    persistence: FixturePersistence,
    kind: GoalFixtureKind,
  ) {
    val segmentStartedAt = persistence.now.minus(1, ChronoUnit.HOURS)
    val segmentId = "$parentId:seg:$segmentStartedAt"
    val finishedAt = persistence.now.minus(5, ChronoUnit.MINUTES)
    unit.lifecycleTelemetry.goalStarted(
      GoalStartedRecord(
        issueKey = case.issueKey,
        featureName = "Desktop fixture ${case.id}",
        workflowId = segmentId,
        subtaskTotal = 1,
        resumed = false,
        startedAt = segmentStartedAt.toString(),
        status = WorkflowStatus.RUNNING.wireValue,
        mode = "runtime",
        parentWorkflowId = parentId,
      ),
      FIXTURE_TELEMETRY_LEVEL,
    )
    val segment =
      GoalSegmentFinish(
        case = case,
        parentId = parentId,
        segmentId = segmentId,
        startedAt = segmentStartedAt,
        finishedAt = finishedAt,
      )
    if (kind.abandoned) {
      check(unit.lifecycleTelemetry.cancelGoalIssue(parentId, case.issueKey, finishedAt)) {
        "Desktop fixture goal cancellation was rejected for ${case.issueKey}"
      }
      finishGoalSegment(unit, segment, WorkflowStatus.ABANDONED.wireValue, completed = false)
      return
    }
    val settlement = kind.settlement ?: return
    finishGoalSegment(unit, segment, settlement, kind.completed)
  }

  private fun finishGoalSegment(
    unit: UnitOfWork,
    segment: GoalSegmentFinish,
    settlement: String,
    completed: Boolean,
  ) {
    val blocked =
      settlement == WorkflowStatus.BLOCKED.wireValue || settlement == WorkflowStatus.FAILED.wireValue
    unit.lifecycleTelemetry.goalFinished(
      GoalFinishedRecord(
        issueKey = segment.case.issueKey,
        workflowId = segment.segmentId,
        status = settlement,
        startedAt = segment.startedAt.toString(),
        finishedAt = segment.finishedAt.toString(),
        durationMs = ChronoUnit.MINUTES.between(segment.startedAt, segment.finishedAt) * 60_000,
        subtasksComplete = if (completed) 1 else 0,
        subtasksBlocked = if (blocked) 1 else 0,
        subtasksSkipped = 0,
        mode = "runtime",
        parentWorkflowId = segment.parentId,
      ),
      FIXTURE_TELEMETRY_LEVEL,
    )
    if (!completed) return
    unit.lifecycleTelemetry.goalIssueFinished(
      GoalIssueFinishedRecord(
        issueKey = segment.case.issueKey,
        parentWorkflowId = segment.parentId,
        status = WorkflowStatus.COMPLETED.wireValue,
        subtasksComplete = 1,
        subtasksBlocked = 0,
        subtasksSkipped = 0,
        finishedAt = segment.finishedAt.toString(),
        mode = "runtime",
      ),
      FIXTURE_TELEMETRY_LEVEL,
    )
  }

  private fun persistVerify(
    unit: UnitOfWork,
    case: DesktopFixtureVerifyCase,
    persistence: FixturePersistence,
  ) {
    val updatedAt =
      if (case.stale) {
        persistence.now.minus(IdeStatusPolicy.VERIFY_ABANDONED_AFTER).minus(1, ChronoUnit.HOURS)
      } else {
        persistence.now
      }
    val artifacts =
      buildMap<String, Any?> {
        put(
          VerifyFixtureArtifactKeys.INPUT_CONTEXT,
          mapOf(VerifyFixtureArtifactKeys.REPO_ROOT to persistence.checkout.toString()),
        )
        case.supersededBy?.let { supersededBy ->
          put(
            VerifyFixtureArtifactKeys.SESSION_NOTES,
            mapOf(VerifyFixtureArtifactKeys.SUPERSEDED_BY to supersededBy),
          )
        }
      }
    val verify = verifySnapshot("fixture-${case.id}", case.status, updatedAt, case.currentStep, artifacts)
    unit.workflowStates.saveRecord(WorkflowFamily.VERIFY, verify.toRecord().copy(issueKey = case.issueKey))
  }

  private fun persistStandalone(
    unit: UnitOfWork,
    case: DesktopFixtureStandaloneCase,
    index: Int,
    persistence: FixturePersistence,
  ) {
    val now = persistence.now
    val invocationId = "fixture-${case.id}"
    val record =
      unit.standalonePhaseStatuses.register(
        StandalonePhaseStatusRegistration(
          repositoryIdentity = persistence.checkoutRepositoryIdentity,
          branchCorrelation = "desktop-fixture",
          issueKey = "${DesktopFixtureCatalog.ISSUE_PREFIX}-130",
          workflowId = null,
          invocationId = invocationId,
          phaseId = case.phaseId,
          executionId = "execution-$invocationId",
          lifecycleState = "active",
          currentStep = case.phaseId,
          startedAt = now.minus(index.toLong(), ChronoUnit.MINUTES),
          leaseOwner = persistence.owner.processBirthToken,
          leaseGeneration = 1,
          leaseExpiresAt = now.plus(24, ChronoUnit.HOURS),
          processOwner = persistence.owner,
        ),
      )
    val terminal = case.lifecycle in setOf("failed", "canceled", "terminal")
    val result =
      unit.standalonePhaseStatuses.update(
        StandalonePhaseStatusUpdate(
          executionId = record.executionId,
          expectedRevision = record.statusRevision,
          leaseOwner = record.leaseOwner,
          leaseGeneration = record.leaseGeneration,
          lifecycleState = case.lifecycle,
          currentStep = case.phaseId,
          currentActivity = "Desktop fixture",
          updatedAt = now,
          finishedAt = if (terminal) now else null,
          terminalResult =
            if (case.lifecycle == "terminal") "completed" else case.lifecycle.takeIf { terminal },
        ),
      )
    check(result == StandalonePhaseStatusUpdateResult.ACCEPTED) {
      "Desktop fixture standalone transition '${case.id}' was rejected: $result"
    }
  }

  private fun writeBundles(specsRoot: Path) {
    Files.createDirectories(specsRoot)
    DesktopFixtureCatalog.bundles.forEach { bundle ->
      val directory = Files.createDirectories(specsRoot.resolve(bundle.directory))
      Files.writeString(directory.resolve("spec.md"), "# ${bundle.directory}\n\nGenerated desktop QA fixture.\n")
      val issueKey = bundle.issueKey ?: return@forEach
      Files.writeString(
        directory.resolve("spec_subtask_1.md"),
        "# ${bundle.directory} subtask\n\nFixture phase.\n",
      )
      val goal = DesktopFixtureCatalog.goals.firstOrNull { it.id == bundle.goalId }
      val subtaskStatus =
        if (bundle.manifestStatus == DecompositionStatus.COMPLETE.wireValue) {
          DecompositionStatus.COMPLETE.wireValue
        } else {
          DecompositionStatus.PENDING.wireValue
        }
      val intent = intentFor(1, subtaskStatus)
      Files.writeString(
        directory.resolve("decomposition-manifest.yaml"),
        """
        contract_version: "0.5"
        issue_key: "$issueKey"
        feature_name: "Desktop fixture ${bundle.directory}"
        parent_spec_path: "${specReference(bundle.directory, "spec.md")}"
        spec_source: local
        status: "${bundle.manifestStatus}"
        execution_model: same_branch_commit_per_subtask
        base_branch: main
        feature_branch: "fixture/${bundle.directory}"
        stack_branches: []
        current_subtask_intent:
          subtask_id: ${intent.subtaskId}
          action: ${intent.action}
        subtasks:
          - id: 1
            name: "${bundle.directory}"
            spec_path: "${specReference(bundle.directory, "spec_subtask_1.md")}"
            status: $subtaskStatus
            branch: null
            commit_sha: null
            workflow_id: ${goal?.let { "fixture-${it.id}-child" } ?: "null"}
            blocked_reason: null
            last_resumable_step: null
            dependencies: []
        """.trimIndent() + "\n",
      )
    }
  }

  private fun goalManifest(
    case: DesktopFixtureGoalCase,
    childId: String,
    subtaskStatus: DecompositionStatus,
    commitSha: String?,
  ): DecompositionManifest {
    val subtask =
      DecompositionSubtask(
        id = 1,
        name = "Desktop fixture phase",
        specPath = specReference(case.bundleDirectory, "spec_subtask_1.md"),
        status = subtaskStatus.wireValue,
        commitSha = commitSha,
        blockedReason = if (subtaskStatus == DecompositionStatus.BLOCKED) "Desktop fixture ${case.id}" else null,
        lastResumableStep = if (case.status == WorkflowStatus.ABANDONED) TASK_STEP else null,
        workflowId = childId,
      )
    return DecompositionManifest(
      issueKey = case.issueKey,
      featureName = "Desktop fixture ${case.id}",
      parentSpecPath = specReference(case.bundleDirectory, "spec.md"),
      baseBranch = "main",
      featureBranch = "fixture/${case.issueKey.lowercase()}",
      currentSubtaskIntent = intentFor(subtask.id, subtask.status),
      subtasks = listOf(subtask),
    ).withParentStatus()
  }

  private fun specReference(
    directory: String,
    filename: String,
  ): String = "${ProjectStoreLayout.SPECS_PREFIX}$directory/$filename"

  private fun parentArtifacts(
    manifest: DecompositionManifest,
    parentId: String,
    childId: String,
    completed: Boolean,
  ): Map<String, Any?> {
    val projected =
      goalParentArtifactProjection(
        DurableWorkflowArtifacts.EMPTY,
        FeatureTaskRuntimeWorkflowArtifactMap.from(encodeDecompositionManifestWireMap(manifest)),
      )
    if (!completed) return projected
    return projected + historyDraftEntry(parentId, childId)
  }

  private fun historyDraftEntry(
    parentId: String,
    childId: String,
  ): Pair<String, Any?> {
    val draft =
      GoalChildHistoryDraft(
        parentWorkflowId = parentId,
        subtaskId = 1,
        childWorkflowId = childId,
        disposition = GoalChildHistoryDraftDisposition.SKIP,
        skipReason = "Generated desktop fixture data. No real feature execution or history was performed.",
      )
    draft.validate()
    val keys = GoalChildHistoryDraftPayloadKeys
    return DurableWorkflowArtifactFamily.GOAL_CHILD_HISTORY_DRAFTS.entry(
      listOf(
        mapOf(
          keys.CONTRACT_VERSION to GOAL_CHILD_HISTORY_DRAFT_CONTRACT_VERSION,
          keys.PARENT_WORKFLOW_ID to draft.parentWorkflowId,
          keys.SUBTASK_ID to draft.subtaskId,
          keys.CHILD_WORKFLOW_ID to draft.childWorkflowId,
          keys.DISPOSITION to draft.disposition.wireValue,
          keys.SKIP_REASON to draft.skipReason,
        ),
      ),
    )
  }

  private fun childArtifacts(
    case: DesktopFixtureGoalCase,
    parentId: String,
    childId: String,
    persistence: FixturePersistence,
    completed: Boolean,
  ): Map<String, Any?> =
    buildMap {
      put(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label(),
        FeatureTaskRuntimeGoalContinuationArtifact(
          issueKey = case.issueKey,
          subtaskId = 1,
          suppressPr = false,
          goalBranch = "fixture/${case.issueKey.lowercase()}",
          parentWorkflowId = parentId,
          codeReviewMode = if (completed) CodeReviewExecutionMode.INLINE else CodeReviewExecutionMode.DEFAULT,
        ),
      )
      if (!completed) return@buildMap
      put(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.label(),
        decodedCompletionPlan(persistence.completionPlan),
      )
      put(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_OUTCOME.label(),
        FeatureTaskRuntimeGoalContinuationOutcome(
          issueKey = case.issueKey,
          subtaskId = 1,
          status = GoalRunnerTerminalStatus.COMPLETE,
          workflowId = childId,
          commitSha = persistence.completionCommitSha,
          lastResumableStep = COMPLETION_STEP,
        ).toPersistenceWire(),
      )
    }

  private fun decodedCompletionPlan(plan: ValidatedFeatureTaskRuntimeExecutionPlan): Map<String, Any?> =
    JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(plan.encoded().toString(Charsets.UTF_8)))
      ?: error("Validated desktop fixture execution plan must decode to an object")

  private fun taskSnapshot(
    workflowId: String,
    status: WorkflowStatus,
    now: Instant,
    currentStep: String,
    artifacts: Map<String, Any?>,
  ): WorkflowStateSnapshot {
    val phases = fixturePhases(currentStep, stepStatus(status))
    val startedAt = now.minus(1, ChronoUnit.HOURS)
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    return WorkflowStateSnapshot(
      workflowId = workflowId,
      sessionId = "session-$workflowId",
      workflowName = definition.workflowName,
      contractVersion = definition.contractVersion,
      workflowStatus = status,
      currentStepId = currentStep,
      steps = phases.map { (phaseId, phaseStatus) -> WorkflowStepState(phaseId, phaseStatus, attemptOf(phaseStatus)) },
      artifacts = DurableWorkflowArtifacts.fromMap(artifacts + phaseRecordArtifact(phases, startedAt, now)),
      startedAt = startedAt,
      updatedAt = now,
      finishedAt = workflowFinishedAt(status, now),
      mode = FeatureTaskWorkflowMode.RUNTIME,
    )
  }

  private fun verifySnapshot(
    workflowId: String,
    status: WorkflowStatus,
    now: Instant,
    currentStep: String,
    artifacts: Map<String, Any?>,
  ): WorkflowStateSnapshot {
    val definition = WorkflowFamily.VERIFY.definition
    val current = stepStatus(status)
    return WorkflowStateSnapshot(
      workflowId = workflowId,
      sessionId = "session-$workflowId",
      workflowName = definition.workflowName,
      contractVersion = definition.contractVersion,
      workflowStatus = status,
      currentStepId = currentStep,
      steps =
        listOf(
          WorkflowStepState(VERIFY_PRIOR_STEP, WorkflowStepStatus.COMPLETED, 1),
          WorkflowStepState(currentStep, current, attemptOf(current)),
        ),
      artifacts = DurableWorkflowArtifacts.fromMap(artifacts),
      startedAt = now.minus(1, ChronoUnit.HOURS),
      updatedAt = now,
      finishedAt = workflowFinishedAt(status, now),
      mode = null,
    )
  }

  private fun phaseRecordArtifact(
    phases: List<Pair<String, WorkflowStepStatus>>,
    startedAt: Instant,
    now: Instant,
  ): Map<String, Any?> {
    val startedPhases = phases.filter { (_, phaseStatus) -> phaseStatus != WorkflowStepStatus.PENDING }
    val records =
      startedPhases
        .associate { (phaseId, phaseStatus) ->
          phaseId to
            FeatureTaskRuntimePhaseRecord(
              phaseId = phaseId,
              status = phaseStatus,
              attemptCount = attemptOf(phaseStatus),
              startedAt = startedAt,
              finishedAt =
                if (phaseStatus == WorkflowStepStatus.RUNNING || phaseStatus == WorkflowStepStatus.PAUSED) {
                  null
                } else {
                  now
                },
              resolvedAgentId = FIXTURE_AGENT,
            ).asWorkflowArtifactEntry()
        }
    val ledger =
      startedPhases.mapIndexed { index, (phaseId, phaseStatus) ->
        FeatureTaskRuntimePhaseLedgerEntry(
          action =
            when (phaseStatus) {
              WorkflowStepStatus.COMPLETED -> FeatureTaskRuntimePhaseLedgerAction.COMPLETE
              WorkflowStepStatus.BLOCKED -> FeatureTaskRuntimePhaseLedgerAction.BLOCKED
              WorkflowStepStatus.PAUSED -> FeatureTaskRuntimePhaseLedgerAction.PAUSED
              else -> FeatureTaskRuntimePhaseLedgerAction.START
            },
          sequenceNumber = index,
          timestamp = if (phaseStatus == WorkflowStepStatus.COMPLETED) now else startedAt,
          phaseId = phaseId,
          attemptCount = attemptOf(phaseStatus),
          resolvedAgentId = FIXTURE_AGENT,
        ).asWorkflowArtifactEntry()
      }
    return mapOf(
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(records),
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.entry(ledger),
    )
  }
}

private data class FixturePersistence(
  val now: Instant,
  val checkout: Path,
  val checkoutRepositoryIdentity: String,
  val goalRepositoryIdentity: String,
  val owner: FeatureTaskRuntimeProcessIdentity,
  val completionPlan: ValidatedFeatureTaskRuntimeExecutionPlan,
  val completionCommitSha: String,
)

private data class GoalSegmentFinish(
  val case: DesktopFixtureGoalCase,
  val parentId: String,
  val segmentId: String,
  val startedAt: Instant,
  val finishedAt: Instant,
)

private data class GoalFixtureKind(
  val workflowStatus: WorkflowStatus,
  val subtaskStatus: DecompositionStatus,
  val settlement: String?,
  val abandoned: Boolean,
  val completed: Boolean,
  val stopped: Boolean,
)

private fun DesktopFixtureGoalCase.fixtureKind(): GoalFixtureKind {
  val completed = status == WorkflowStatus.COMPLETED
  val stopped = status == WorkflowStatus.BLOCKED || status == WorkflowStatus.FAILED
  val subtaskStatus =
    when {
      completed -> DecompositionStatus.COMPLETE
      stopped -> DecompositionStatus.BLOCKED
      else -> DecompositionStatus.IN_PROGRESS
    }
  val settlement =
    when (status) {
      WorkflowStatus.PAUSED,
      WorkflowStatus.BLOCKED,
      WorkflowStatus.FAILED,
      WorkflowStatus.COMPLETED,
      -> status.wireValue
      else -> null
    }
  return GoalFixtureKind(
    workflowStatus = status,
    subtaskStatus = subtaskStatus,
    settlement = settlement,
    abandoned = status == WorkflowStatus.ABANDONED,
    completed = completed,
    stopped = stopped,
  )
}

private fun fixturePhases(
  currentStep: String,
  currentStatus: WorkflowStepStatus,
): List<Pair<String, WorkflowStepStatus>> {
  val definition = FeatureTaskRuntimePhaseWorkflowDefinition
  if (currentStep == definition.PHASE_COMMIT_PUSH) {
    return listOf(
      definition.PHASE_PREPLAN to WorkflowStepStatus.COMPLETED,
      definition.PHASE_PLAN to WorkflowStepStatus.COMPLETED,
      definition.PHASE_IMPLEMENT to WorkflowStepStatus.COMPLETED,
      definition.PHASE_WRITE_HISTORY to WorkflowStepStatus.SKIPPED,
      definition.PHASE_COMMIT_PUSH to WorkflowStepStatus.COMPLETED,
    )
  }
  return listOf(
    definition.PHASE_PREPLAN to WorkflowStepStatus.COMPLETED,
    definition.PHASE_PLAN to WorkflowStepStatus.COMPLETED,
  ).filter { (phaseId, _) -> phaseId != currentStep } + (currentStep to currentStatus)
}

private fun stepStatus(status: WorkflowStatus): WorkflowStepStatus =
  when (status) {
    WorkflowStatus.RUNNING -> WorkflowStepStatus.RUNNING
    WorkflowStatus.COMPLETED -> WorkflowStepStatus.COMPLETED
    WorkflowStatus.FAILED -> WorkflowStepStatus.FAILED
    WorkflowStatus.BLOCKED -> WorkflowStepStatus.BLOCKED
    WorkflowStatus.PAUSED -> WorkflowStepStatus.PAUSED
    WorkflowStatus.ABANDONED -> WorkflowStepStatus.SKIPPED
    WorkflowStatus.PENDING -> WorkflowStepStatus.PENDING
    WorkflowStatus.TIMED_OUT -> WorkflowStepStatus.FAILED
  }

private fun attemptOf(status: WorkflowStepStatus): Int = if (status == WorkflowStepStatus.PENDING) 0 else 1

private fun workflowFinishedAt(
  status: WorkflowStatus,
  now: Instant,
): Instant? =
  when (status) {
    WorkflowStatus.COMPLETED,
    WorkflowStatus.FAILED,
    WorkflowStatus.ABANDONED,
    WorkflowStatus.TIMED_OUT,
    -> now
    else -> null
  }

private const val FIXTURE_AGENT = "desktop-fixture"
private const val FIXTURE_TELEMETRY_LEVEL = "anonymous"
private const val VERIFY_PRIOR_STEP = "collect_inputs"
private const val TASK_STEP = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
private const val COMPLETION_STEP = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH

private object VerifyFixtureArtifactKeys {
  const val INPUT_CONTEXT = "input_context"
  const val REPO_ROOT = "repo_root"
  const val SESSION_NOTES = "session_notes"
  const val SUPERSEDED_BY = "superseded_by"
}
